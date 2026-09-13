package io.jafra.analyzer.recordings;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;
import io.jafra.analyzer.storage.RecordingManifest;
import io.jafra.analyzer.storage.StitchCache;

@ApplicationScoped
public class RecordingCatalog {
    private final ChunkStore store;
    private final StitchCache stitchCache;
    private final ConcurrentHashMap<String, CachedSpan> timeCache = new ConcurrentHashMap<>();

    @Inject
    public RecordingCatalog(ChunkStore store, StitchCache stitchCache) {
        this.store = store;
        this.stitchCache = stitchCache;
    }

    public RecordingListResponse list(String namespace, String pod, String container) {
        Map<WorkloadKey, List<RecordingSummary>> grouped = new LinkedHashMap<>();
        for (IndexedRecording recording : index()) {
            if (!matches(recording, namespace, pod, container)) {
                continue;
            }
            grouped.computeIfAbsent(recording.key(), ignored -> new ArrayList<>()).add(recording.summary());
        }
        List<WorkloadRecordings> workloads = new ArrayList<>();
        grouped.forEach((key, recordings) -> {
            recordings.sort(Comparator.comparingInt(RecordingSummary::sequence).thenComparing(RecordingSummary::filename));
            workloads.add(new WorkloadRecordings(key.namespace(), key.pod(), key.container(), recordings));
        });
        workloads.sort(Comparator
                .comparing(WorkloadRecordings::namespace)
                .thenComparing(WorkloadRecordings::pod)
                .thenComparing(WorkloadRecordings::container));
        return new RecordingListResponse(workloads);
    }

    public WorkloadRecordings requireWorkload(String namespace, String pod, String container) {
        RecordingListResponse listed = list(namespace, pod, container);
        if (listed.workloads().size() != 1) {
            throw new NotFoundException("no recordings for namespace/%s/pod/%s/container/%s".formatted(namespace, pod, container));
        }
        return listed.workloads().getFirst();
    }

    public IndexedRecording requireRecording(String namespace, String pod, String container, String filename) {
        List<IndexedRecording> matches = index().stream()
                .filter(recording -> matches(recording, namespace, pod, container))
                .filter(recording -> recording.summary().filename().equals(filename))
                .toList();
        if (matches.size() != 1) {
            throw new NotFoundException("recording %s not found for namespace/%s/pod/%s/container/%s"
                    .formatted(filename, namespace, pod, container));
        }
        return matches.getFirst();
    }

    public IndexedRecording latestClosed(String namespace, String pod, String container) {
        WorkloadRecordings workload = requireWorkload(namespace, pod, container);
        List<RecordingSummary> recordings = workload.recordings();
        RecordingSummary selected = recordings.size() >= 2
                ? recordings.get(recordings.size() - 2)
                : recordings.getLast();
        return requireRecording(namespace, pod, container, selected.filename());
    }

    public Optional<WindowSelection> openWindow(
            String namespace,
            String pod,
            String container,
            ReportWindow window)
            throws IOException {
        List<IndexedRecording> all = index().stream()
                .filter(recording -> matches(recording, namespace, pod, container))
                .toList();
        if (all.isEmpty()) {
            throw new NotFoundException("no recordings for namespace/%s/pod/%s/container/%s"
                    .formatted(namespace, pod, container));
        }
        Instant coverageStart = null;
        Instant coverageStop = null;
        List<IndexedRecording> dated = new ArrayList<>();
        for (IndexedRecording recording : all) {
            JfrTimeRange.TimeSpan span = recording.span();
            if (span == null) {
                continue;
            }
            dated.add(recording);
            coverageStart = coverageStart == null || span.start().isBefore(coverageStart) ? span.start() : coverageStart;
            coverageStop = coverageStop == null || span.stop().isAfter(coverageStop) ? span.stop() : coverageStop;
        }
        if (dated.isEmpty() || coverageStart == null || coverageStop == null) {
            throw new NotFoundException("recordings have no usable start/stop timestamps for namespace/%s/pod/%s/container/%s"
                    .formatted(namespace, pod, container));
        }
        Instant from = window.resolvedFrom(coverageStart);
        Instant to = window.resolvedTo(coverageStop);
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be earlier than to");
        }
        List<IndexedRecording> selected = new ArrayList<>();
        Instant start = null;
        Instant stop = null;
        long bytes = 0;
        int chunks = 0;
        for (IndexedRecording recording : dated) {
            if (!recording.span().overlaps(from, to)) {
                continue;
            }
            selected.add(recording);
            start = start == null || recording.span().start().isBefore(start) ? recording.span().start() : start;
            stop = stop == null || recording.span().stop().isAfter(stop) ? recording.span().stop() : stop;
            bytes += recording.summary().bytes();
            chunks += recording.summary().chunks();
        }
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        Instant needFrom = from.isBefore(coverageStart) ? coverageStart : from;
        Instant needTo = to.isAfter(coverageStop) ? coverageStop : to;
        if (needFrom.isAfter(needTo)) {
            return Optional.empty();
        }
        return Optional.of(openCached(
                selected, dated, start, stop, from, to, needFrom, needTo, bytes, chunks));
    }

    public WindowSelection singleFile(IndexedRecording recording) throws IOException {
        JfrTimeRange.TimeSpan span = recording.span();
        return openCached(
                List.of(recording),
                List.of(recording),
                span == null ? null : span.start(),
                span == null ? null : span.stop(),
                null,
                null,
                span == null ? null : span.start(),
                span == null ? null : span.stop(),
                recording.summary().bytes(),
                recording.summary().chunks());
    }

    private WindowSelection openCached(
            List<IndexedRecording> selected,
            List<IndexedRecording> workloadDated,
            Instant start,
            Instant stop,
            Instant from,
            Instant to,
            Instant needFrom,
            Instant needTo,
            long bytes,
            int chunks)
            throws IOException {
        List<String> recordingIds = selected.stream().map(IndexedRecording::recordingId).toList();
        WorkloadKey key = selected.getFirst().key();
        String workloadKey = workloadCacheKey(key);
        String cacheKey = cacheKey(key, recordingIds);
        String fingerprint = dataFingerprint(workloadDated);
        StitchCache.Lease lease = stitchCache.acquireCovering(
                cacheKey,
                workloadKey,
                recordingIds,
                needFrom,
                needTo,
                start,
                stop,
                fingerprint);
        return new WindowSelection(
                key,
                selected,
                lease.path(),
                lease,
                start,
                stop,
                from,
                to,
                bytes,
                chunks);
    }

    static String workloadCacheKey(WorkloadKey key) {
        return key.namespace() + "|" + key.pod() + "|" + key.container();
    }

    static String cacheKey(WorkloadKey key, List<String> recordingIds) {
        return workloadCacheKey(key)
                + "|"
                + recordingIds.stream().collect(Collectors.joining(","));
    }

    static String dataFingerprint(List<IndexedRecording> dated) {
        return dated.stream()
                .sorted(Comparator.comparing(IndexedRecording::recordingId))
                .map(recording -> recording.recordingId()
                        + ":"
                        + recording.summary().bytes()
                        + ":"
                        + recording.summary().chunks())
                .collect(Collectors.joining(","));
    }

    List<IndexedRecording> index() {
        List<IndexedRecording> recordings = new ArrayList<>();
        for (String recordingId : store.recordingIds()) {
            ChunkMetadata sample = store.sample(recordingId);
            if (sample == null) {
                continue;
            }
            String podName = store.podName(sample.podUid(), sample.podName());
            if (podName == null || podName.isBlank()) {
                continue;
            }
            RecordingManifest manifest = store.manifest(recordingId);
            long bytes = manifest.stitchedBytes;
            if (bytes <= 0) {
                continue;
            }
            String filename = sample.physicalFilename();
            JfrTimeRange.TimeSpan span = resolveTimes(recordingId);
            RecordingSummary summary = new RecordingSummary(
                    filename,
                    fileSequence(filename),
                    bytes,
                    manifest.chunkIds == null ? 0 : manifest.chunkIds.size(),
                    span == null ? null : span.startIso(),
                    span == null ? null : span.endIso(),
                    reportUrl(sample.namespace(), podName, sample.containerName(), filename));
            recordings.add(new IndexedRecording(
                    new WorkloadKey(sample.namespace(), podName, sample.containerName()),
                    recordingId,
                    summary,
                    span));
        }
        recordings.sort(Comparator
                .comparing((IndexedRecording recording) -> recording.key().namespace())
                .thenComparing(recording -> recording.key().pod())
                .thenComparing(recording -> recording.key().container())
                .thenComparingInt(recording -> recording.summary().sequence())
                .thenComparing(recording -> recording.summary().filename()));
        return recordings;
    }

    private JfrTimeRange.TimeSpan resolveTimes(String recordingId) {
        List<ChunkMetadata> contiguous = store.contiguousChunks(recordingId);
        long fingerprint = contiguous.stream().mapToLong(ChunkMetadata::chunkLength).sum();
        fingerprint = fingerprint * 31 + contiguous.size();
        CachedSpan cached = timeCache.get(recordingId);
        if (cached != null && cached.fingerprint() == fingerprint) {
            return cached.span();
        }
        JfrTimeRange.TimeSpan span = JfrTimeRange.ofChunks(contiguous);
        if (span == null) {
            Instant start = null;
            Instant stop = null;
            for (ChunkMetadata chunk : contiguous) {
                JfrTimeRange.TimeSpan fromFile = JfrTimeRange.ofFile(store.payloadPath(chunk.chunkId()));
                if (fromFile == null) {
                    continue;
                }
                start = start == null || fromFile.start().isBefore(start) ? fromFile.start() : start;
                stop = stop == null || fromFile.stop().isAfter(stop) ? fromFile.stop() : stop;
            }
            span = JfrTimeRange.TimeSpan.of(start, stop);
        }
        timeCache.put(recordingId, new CachedSpan(fingerprint, span));
        return span;
    }

    private static boolean matches(IndexedRecording recording, String namespace, String pod, String container) {
        WorkloadKey key = recording.key();
        return blankOrEqual(namespace, key.namespace())
                && blankOrEqual(pod, key.pod())
                && blankOrEqual(container, key.container());
    }

    private static boolean blankOrEqual(String expected, String actual) {
        return expected == null || expected.isBlank() || expected.equals(actual);
    }

    static int fileSequence(String filename) {
        if (filename == null || !filename.startsWith("profile-") || !filename.endsWith(".jfr")) {
            return 0;
        }
        try {
            return Integer.parseInt(filename.substring("profile-".length(), filename.length() - 4));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    static String reportUrl(String namespace, String pod, String container, String filename) {
        return "/api/v1/namespaces/"
                + encode(namespace)
                + "/pods/"
                + encode(pod)
                + "/containers/"
                + encode(container)
                + "/recordings/"
                + encode(filename)
                + "/report";
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(Objects.requireNonNullElse(value, ""), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    public record WorkloadKey(String namespace, String pod, String container) {}

    public record IndexedRecording(
            WorkloadKey key,
            String recordingId,
            RecordingSummary summary,
            JfrTimeRange.TimeSpan span) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecordingSummary(
            String filename,
            int sequence,
            long bytes,
            int chunks,
            String start,
            String end,
            String reportUrl) {}

    public record WorkloadRecordings(
            String namespace, String pod, String container, List<RecordingSummary> recordings) {}

    public record RecordingListResponse(List<WorkloadRecordings> workloads) {}

    public record WindowSelection(
            WorkloadKey key,
            List<IndexedRecording> recordings,
            Path jfr,
            StitchCache.Lease lease,
            Instant start,
            Instant stop,
            Instant from,
            Instant to,
            long bytes,
            int chunks)
            implements Closeable {
        @Override
        public void close() {
            if (lease != null) {
                lease.close();
            }
        }

        /** @deprecated use lease lifecycle; always false for stitch-cache entries. */
        public boolean temporary() {
            return false;
        }
    }

    private record CachedSpan(long fingerprint, JfrTimeRange.TimeSpan span) {}
}

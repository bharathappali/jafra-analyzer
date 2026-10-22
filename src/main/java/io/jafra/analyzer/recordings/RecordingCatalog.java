package io.jafra.analyzer.recordings;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.jafra.analyzer.index.ChunkIndexRow;
import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;

@ApplicationScoped
public class RecordingCatalog {
    private final ChunkStore store;
    private final ConcurrentHashMap<String, CachedSpan> timeCache = new ConcurrentHashMap<>();

    @Inject
    public RecordingCatalog(ChunkStore store) {
        this.store = store;
    }

    public RecordingListResponse list(String namespace, String pod, String container) {
        return list(null, namespace, pod, container);
    }

    public RecordingListResponse list(String cluster, String namespace, String pod, String container) {
        List<IndexedRecording> indexed = index(cluster, namespace, pod, container);
        Map<WorkloadKey, Set<String>> clusters = new LinkedHashMap<>();
        for (IndexedRecording recording : indexed) {
            clusters.computeIfAbsent(recording.key(), ignored -> new LinkedHashSet<>()).add(recording.clusterId());
        }
        Map<WorkloadKey, List<RecordingSummary>> grouped = new LinkedHashMap<>();
        for (IndexedRecording recording : indexed) {
            boolean ambiguous = clusters.getOrDefault(recording.key(), Set.of()).size() > 1;
            grouped.computeIfAbsent(recording.key(), ignored -> new ArrayList<>())
                    .add(ambiguous ? recording.summaryWithCluster() : recording.summary());
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
        return requireWorkload(null, namespace, pod, container);
    }

    public WorkloadRecordings requireWorkload(String cluster, String namespace, String pod, String container) {
        RecordingListResponse listed = list(cluster, namespace, pod, container);
        if (listed.workloads().size() != 1) {
            throw new NotFoundException("no recordings for namespace/%s/pod/%s/container/%s".formatted(namespace, pod, container));
        }
        return listed.workloads().getFirst();
    }

    public IndexedRecording requireRecording(String namespace, String pod, String container, String filename) {
        return requireRecording(null, namespace, pod, container, filename);
    }

    public IndexedRecording requireRecording(
            String cluster, String namespace, String pod, String container, String filename) {
        List<IndexedRecording> matches = index(cluster, namespace, pod, container).stream()
                .filter(recording -> recording.summary().filename().equals(filename))
                .toList();
        if (matches.size() != 1) {
            throw new NotFoundException("recording %s not found for namespace/%s/pod/%s/container/%s"
                    .formatted(filename, namespace, pod, container));
        }
        return matches.getFirst();
    }

    public IndexedRecording latestClosed(String namespace, String pod, String container) {
        return latestClosed(null, namespace, pod, container);
    }

    public IndexedRecording latestClosed(String cluster, String namespace, String pod, String container) {
        WorkloadRecordings workload = requireWorkload(cluster, namespace, pod, container);
        List<RecordingSummary> recordings = workload.recordings();
        RecordingSummary selected = recordings.size() >= 2
                ? recordings.get(recordings.size() - 2)
                : recordings.getLast();
        return requireRecording(cluster, namespace, pod, container, selected.filename());
    }

    public Optional<WindowSelection> openWindow(
            String namespace,
            String pod,
            String container,
            ReportWindow window)
            throws IOException {
        return openWindow(null, namespace, pod, container, window);
    }

    public Optional<WindowSelection> openWindow(
            String cluster,
            String namespace,
            String pod,
            String container,
            ReportWindow window)
            throws IOException {
        long fromNs = window.from() == null ? Long.MIN_VALUE : epochNanos(window.from());
        long toNs = window.to() == null ? Long.MAX_VALUE : epochNanos(window.to());
        List<IndexedRecording> ranged = index(cluster, namespace, pod, container, fromNs, toNs, true);
        if (!ranged.isEmpty()) {
            Optional<WindowSelection> opened = openResolved(ranged, window);
            if (opened.isPresent()) {
                return opened;
            }
        }
        List<IndexedRecording> all = index(cluster, namespace, pod, container);
        if (all.isEmpty()) {
            throw new NotFoundException("no recordings for namespace/%s/pod/%s/container/%s"
                    .formatted(namespace, pod, container));
        }
        boolean dated = false;
        for (IndexedRecording recording : all) {
            if (recording.span() != null) {
                dated = true;
                break;
            }
        }
        if (!dated) {
            throw new NotFoundException("recordings have no usable start/stop timestamps for namespace/%s/pod/%s/container/%s"
                    .formatted(namespace, pod, container));
        }
        return Optional.empty();
    }

    private Optional<WindowSelection> openResolved(List<IndexedRecording> all, ReportWindow window) throws IOException {
        if (all.isEmpty()) {
            return Optional.empty();
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
            return Optional.empty();
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
        return Optional.of(openSelection(selected, start, stop, from, to, bytes, chunks));
    }

    public WindowSelection singleFile(IndexedRecording recording) throws IOException {
        JfrTimeRange.TimeSpan span = recording.span();
        return openSelection(
                List.of(recording),
                span == null ? null : span.start(),
                span == null ? null : span.stop(),
                null,
                null,
                recording.summary().bytes(),
                recording.summary().chunks());
    }

    private WindowSelection openSelection(
            List<IndexedRecording> selected,
            Instant start,
            Instant stop,
            Instant from,
            Instant to,
            long bytes,
            int chunks)
            throws IOException {
        List<Path> chunkFiles = new ArrayList<>();
        for (IndexedRecording recording : selected) {
            chunkFiles.addAll(recording.chunkFiles());
        }
        if (chunkFiles.isEmpty()) {
            throw new java.nio.file.NoSuchFileException("recording has no stored payload");
        }
        return new WindowSelection(
                selected.getFirst().key(),
                selected,
                List.copyOf(chunkFiles),
                start,
                stop,
                from,
                to,
                bytes,
                chunks);
    }

    List<IndexedRecording> index() {
        return index(null, null, null, null);
    }

    List<IndexedRecording> index(String cluster, String namespace, String pod, String container) {
        return index(cluster, namespace, pod, container, Long.MIN_VALUE, Long.MAX_VALUE, false);
    }

    private List<IndexedRecording> index(
            String cluster,
            String namespace,
            String pod,
            String container,
            long fromNs,
            long toNs,
            boolean ranged) {
        String clusterId = cluster == null || cluster.isBlank() ? null : cluster;
        List<ChunkIndexRow> matched;
        try {
            matched = new ArrayList<>(store.index().findRange(clusterId, namespace, pod, container, fromNs, toNs));
            if (ranged) {
                for (ChunkIndexRow undated : store.index().findUndated(clusterId, namespace, pod, container)) {
                    boolean seen = false;
                    for (ChunkIndexRow existing : matched) {
                        if (existing.chunkId().equals(undated.chunkId())) {
                            seen = true;
                            break;
                        }
                    }
                    if (!seen) {
                        matched.add(undated);
                    }
                }
            }
        } catch (IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
        Map<String, List<ChunkIndexRow>> grouped = new LinkedHashMap<>();
        for (ChunkIndexRow row : matched) {
            if (row.podName() == null || row.podName().isBlank()) {
                continue;
            }
            String group = row.clusterId() + "\0" + row.recordingId();
            grouped.computeIfAbsent(group, ignored -> new ArrayList<>()).add(row);
        }
        List<IndexedRecording> recordings = new ArrayList<>();
        for (List<ChunkIndexRow> rows : grouped.values()) {
            List<ChunkIndexRow> used = ranged ? sortedByOffset(rows) : contiguousPrefix(rows);
            if (used.isEmpty()) {
                continue;
            }
            ChunkIndexRow sample = used.getFirst();
            long bytes = used.stream().mapToLong(ChunkIndexRow::chunkLength).sum();
            if (bytes <= 0) {
                continue;
            }
            List<Path> files = new ArrayList<>();
            for (ChunkIndexRow row : used) {
                if (row.path() == null || row.path().isBlank()) {
                    continue;
                }
                files.add(store.recordedPayload(row));
            }
            if (files.isEmpty()) {
                continue;
            }
            String filename = sample.physicalFile() == null ? "" : sample.physicalFile();
            JfrTimeRange.TimeSpan span = eventSpan(used);
            if (span == null) {
                span = resolveTimes(used);
            }
            RecordingSummary summary = new RecordingSummary(
                    filename,
                    fileSequence(filename),
                    bytes,
                    used.size(),
                    span == null ? null : span.startIso(),
                    span == null ? null : span.endIso(),
                    reportUrl(sample.namespace(), sample.podName(), sample.containerName(), filename),
                    null);
            recordings.add(new IndexedRecording(
                    new WorkloadKey(sample.namespace(), sample.podName(), sample.containerName()),
                    sample.clusterId(),
                    sample.recordingId(),
                    summary,
                    span,
                    List.copyOf(files)));
        }
        recordings.sort(Comparator
                .comparing((IndexedRecording recording) -> recording.key().namespace())
                .thenComparing(recording -> recording.key().pod())
                .thenComparing(recording -> recording.key().container())
                .thenComparing(IndexedRecording::clusterId, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(recording -> recording.summary().sequence())
                .thenComparing(recording -> recording.summary().filename()));
        return recordings;
    }

    private static List<ChunkIndexRow> sortedByOffset(List<ChunkIndexRow> rows) {
        List<ChunkIndexRow> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator
                .comparingLong((ChunkIndexRow row) -> row.chunkOffset() == null ? 0L : row.chunkOffset())
                .thenComparingLong(ChunkIndexRow::startNs));
        return sorted;
    }

    /** Contiguous prefix from offset 0. A hole stops the prefix. Built from the rows already loaded. */
    private static List<ChunkIndexRow> contiguousPrefix(List<ChunkIndexRow> rows) {
        TreeMap<Long, ChunkIndexRow> byOffset = new TreeMap<>();
        for (ChunkIndexRow row : rows) {
            long offset = row.chunkOffset() == null ? 0L : row.chunkOffset();
            byOffset.put(offset, row);
        }
        List<ChunkIndexRow> prefix = new ArrayList<>();
        long next = 0;
        while (true) {
            ChunkIndexRow row = byOffset.get(next);
            if (row == null) {
                return prefix;
            }
            prefix.add(row);
            next += row.chunkLength();
        }
    }

    private static JfrTimeRange.TimeSpan eventSpan(List<ChunkIndexRow> rows) {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        boolean timed = false;
        for (ChunkIndexRow row : rows) {
            if (row.minEventStartNs() == 0 && row.maxEventEndNs() == 0) {
                continue;
            }
            timed = true;
            min = Math.min(min, row.minEventStartNs());
            max = Math.max(max, row.maxEventEndNs());
        }
        if (!timed || max <= min) {
            return null;
        }
        return JfrTimeRange.TimeSpan.of(nanos(min), nanos(max));
    }

    private static Instant nanos(long epochNs) {
        return Instant.ofEpochSecond(Math.floorDiv(epochNs, 1_000_000_000L), Math.floorMod(epochNs, 1_000_000_000L));
    }

    private static long epochNanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
    }

    private JfrTimeRange.TimeSpan resolveTimes(List<ChunkIndexRow> rows) {
        String recordingId = rows.getFirst().recordingId();
        long fingerprint = rows.stream().mapToLong(ChunkIndexRow::chunkLength).sum();
        fingerprint = fingerprint * 31 + rows.size();
        CachedSpan cached = timeCache.get(recordingId);
        if (cached != null && cached.fingerprint() == fingerprint) {
            return cached.span();
        }
        List<ChunkMetadata> chunks = new ArrayList<>();
        for (ChunkIndexRow row : rows) {
            long duration = Math.max(0, row.endNs() - row.startNs());
            chunks.add(new ChunkMetadata(
                    row.chunkId(),
                    row.recordingId(),
                    row.clusterId(),
                    row.namespace(),
                    row.podUid(),
                    row.podName(),
                    row.containerName(),
                    row.physicalFile() == null ? "" : row.physicalFile(),
                    row.chunkOffset() == null ? 0L : row.chunkOffset(),
                    row.chunkLength(),
                    row.checksum(),
                    row.startNs(),
                    duration));
        }
        JfrTimeRange.TimeSpan span = JfrTimeRange.ofChunks(chunks);
        if (span == null) {
            Instant start = null;
            Instant stop = null;
            for (ChunkIndexRow row : rows) {
                if (row.path() == null || row.path().isBlank()) {
                    continue;
                }
                JfrTimeRange.TimeSpan fromFile = JfrTimeRange.ofFile(store.recordedPayload(row));
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
            String clusterId,
            String recordingId,
            RecordingSummary summary,
            JfrTimeRange.TimeSpan span,
            List<Path> chunkFiles) {

        public RecordingSummary summaryWithCluster() {
            return new RecordingSummary(
                    summary.filename(),
                    summary.sequence(),
                    summary.bytes(),
                    summary.chunks(),
                    summary.start(),
                    summary.end(),
                    summary.reportUrl(),
                    clusterId);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecordingSummary(
            String filename,
            int sequence,
            long bytes,
            int chunks,
            String start,
            String end,
            String reportUrl,
            String clusterId) {}

    public record WorkloadRecordings(
            String namespace, String pod, String container, List<RecordingSummary> recordings) {}

    public record RecordingListResponse(List<WorkloadRecordings> workloads) {}

    public record WindowSelection(
            WorkloadKey key,
            List<IndexedRecording> recordings,
            List<Path> chunkFiles,
            Instant start,
            Instant stop,
            Instant from,
            Instant to,
            long bytes,
            int chunks) {}

    private record CachedSpan(long fingerprint, JfrTimeRange.TimeSpan span) {}
}

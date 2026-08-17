package io.jafra.analyzer.recordings;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;
import io.jafra.analyzer.storage.RecordingManifest;

@ApplicationScoped
public class RecordingCatalog {
    private final ChunkStore store;
    private final ConcurrentHashMap<String, CachedSpan> timeCache = new ConcurrentHashMap<>();

    @Inject
    public RecordingCatalog(ChunkStore store) {
        this.store = store;
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
        Merge merge = concatenate(selected);
        return Optional.of(new WindowSelection(
                selected.getFirst().key(),
                selected,
                merge.path(),
                merge.temporary(),
                start,
                stop,
                from,
                to,
                bytes,
                chunks));
    }

    public WindowSelection singleFile(IndexedRecording recording) {
        JfrTimeRange.TimeSpan span = recording.span();
        return new WindowSelection(
                recording.key(),
                List.of(recording),
                recording.stitched(),
                false,
                span == null ? null : span.start(),
                span == null ? null : span.stop(),
                null,
                null,
                recording.summary().bytes(),
                recording.summary().chunks());
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
            Path stitched = store.stitchedFile(recordingId);
            RecordingManifest manifest = store.manifest(recordingId);
            long bytes = manifest.stitchedBytes;
            if (bytes == 0 && Files.exists(stitched)) {
                try {
                    bytes = Files.size(stitched);
                } catch (Exception ignored) {
                    bytes = 0;
                }
            }
            if (bytes <= 0) {
                continue;
            }
            String filename = sample.physicalFilename();
            JfrTimeRange.TimeSpan span = resolveTimes(recordingId, stitched);
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
                    stitched,
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

    private JfrTimeRange.TimeSpan resolveTimes(String recordingId, Path stitched) {
        long size = 0;
        long mtime = 0;
        try {
            if (Files.exists(stitched)) {
                size = Files.size(stitched);
                mtime = Files.getLastModifiedTime(stitched).toMillis();
            }
        } catch (IOException ignored) {
            return JfrTimeRange.ofChunks(store.chunks(recordingId));
        }
        CachedSpan cached = timeCache.get(recordingId);
        if (cached != null && cached.size() == size && cached.mtime() == mtime) {
            return cached.span();
        }
        JfrTimeRange.TimeSpan span = JfrTimeRange.ofFile(stitched);
        if (span == null) {
            span = JfrTimeRange.ofChunks(store.chunks(recordingId));
        }
        timeCache.put(recordingId, new CachedSpan(size, mtime, span));
        return span;
    }

    private static Merge concatenate(List<IndexedRecording> recordings) throws IOException {
        if (recordings.size() == 1) {
            return new Merge(recordings.getFirst().stitched(), false);
        }
        Path merged = Files.createTempFile("jafra-window-", ".jfr");
        try (FileChannel out = FileChannel.open(
                merged,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            for (IndexedRecording recording : recordings) {
                try (FileChannel in = FileChannel.open(recording.stitched(), StandardOpenOption.READ)) {
                    long copied = 0;
                    long size = in.size();
                    while (copied < size) {
                        long n = in.transferTo(copied, size - copied, out);
                        if (n <= 0) {
                            throw new IOException("short read while merging " + recording.summary().filename());
                        }
                        copied += n;
                    }
                }
            }
            out.force(true);
        } catch (IOException error) {
            Files.deleteIfExists(merged);
            throw error;
        }
        return new Merge(merged, true);
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
            Path stitched,
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
            boolean temporary,
            Instant start,
            Instant stop,
            Instant from,
            Instant to,
            long bytes,
            int chunks)
            implements Closeable {
        @Override
        public void close() {
            if (temporary && jfr != null) {
                try {
                    Files.deleteIfExists(jfr);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private record CachedSpan(long size, long mtime, JfrTimeRange.TimeSpan span) {}

    private record Merge(Path path, boolean temporary) {}
}

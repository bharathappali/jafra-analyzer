package io.jafra.analyzer.ingest.decode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;

/** Compares chunks durable in v1 with the v2 decode queue, per namespace, pod, and container. */
public final class IntegrityReport {
    private IntegrityReport() {}

    public static Map<String, Object> of(ChunkStore store, List<IngestCatalog.CoverageRow> coverage) {
        Map<String, V1> v1 = new TreeMap<>();
        for (ChunkStore.StoredChunk stored : store.storedChunks()) {
            ChunkMetadata metadata = store.readMeta(stored);
            if (metadata == null) {
                continue;
            }
            String key = key(metadata.namespace(), metadata.podName(), metadata.containerName());
            V1 row = v1.computeIfAbsent(key, ignored -> new V1(metadata.namespace(), metadata.podName(), metadata.containerName()));
            row.chunks++;
            long end = metadata.chunkStartTimeNs() + Math.max(0L, metadata.chunkDurationNs());
            row.from = row.from == null ? metadata.chunkStartTimeNs() : Math.min(row.from, metadata.chunkStartTimeNs());
            row.to = row.to == null ? end : Math.max(row.to, end);
        }
        Map<String, V2> v2 = new TreeMap<>();
        for (IngestCatalog.CoverageRow row : coverage) {
            String key = key(row.namespace(), row.pod(), row.container());
            V2 pod = v2.computeIfAbsent(key, ignored -> new V2(row.namespace(), row.pod(), row.container()));
            String status = "ok".equals(row.status()) ? "done" : row.status();
            pod.counts.merge(status == null ? "" : status, row.chunks(), Long::sum);
            pod.eventsSeen += row.eventsSeen();
            pod.rowsWritten += row.rowsWritten();
            if ("done".equals(status)) {
                pod.from = pod.from == null ? row.fromNs() : Math.min(pod.from, row.fromNs());
                pod.to = pod.to == null ? row.toNs() : Math.max(pod.to, row.toNs());
            }
        }
        List<String> keys = new ArrayList<>();
        keys.addAll(v1.keySet());
        for (String key : v2.keySet()) {
            if (!v1.containsKey(key)) {
                keys.add(key);
            }
        }
        keys.sort(Comparator.naturalOrder());
        List<Map<String, Object>> pods = new ArrayList<>();
        for (String key : keys) {
            V1 left = v1.get(key);
            V2 right = v2.get(key);
            long v1Chunks = left == null ? 0L : left.chunks;
            long done = right == null ? 0L : right.counts.getOrDefault("done", 0L);
            long pending = count(right, "pending");
            long decoding = count(right, "decoding");
            long failed = count(right, "failed");
            long quarantined = count(right, "quarantined");
            long lag = v1Chunks - done;
            boolean matched = lag == 0L && pending == 0L && decoding == 0L && failed == 0L && quarantined == 0L;
            Map<String, Object> pod = new LinkedHashMap<>();
            pod.put("namespace", left == null ? right.namespace : left.namespace);
            pod.put("pod", left == null ? right.pod : left.pod);
            pod.put("container", left == null ? right.container : left.container);
            Map<String, Object> v1Body = new LinkedHashMap<>();
            v1Body.put("chunks", v1Chunks);
            v1Body.put("from", left == null ? null : instant(left.from));
            v1Body.put("to", left == null ? null : instant(left.to));
            pod.put("v1", v1Body);
            Map<String, Object> v2Body = new LinkedHashMap<>();
            v2Body.put("pending", pending);
            v2Body.put("decoding", decoding);
            v2Body.put("done", done);
            v2Body.put("failed", failed);
            v2Body.put("quarantined", quarantined);
            v2Body.put("from", right == null ? null : instant(right.from));
            v2Body.put("to", right == null ? null : instant(right.to));
            v2Body.put("events_seen", right == null ? 0L : right.eventsSeen);
            v2Body.put("rows_written", right == null ? 0L : right.rowsWritten);
            pod.put("v2", v2Body);
            pod.put("lag_chunks", lag);
            pod.put("matched", matched);
            pods.add(pod);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pods", pods);
        return body;
    }

    private static long count(V2 row, String status) {
        return row == null ? 0L : row.counts.getOrDefault(status, 0L);
    }

    private static String key(String namespace, String pod, String container) {
        return value(namespace) + "\n" + value(pod) + "\n" + value(container);
    }

    private static String value(String raw) {
        return raw == null ? "" : raw;
    }

    private static String instant(Long nanos) {
        if (nanos == null) {
            return null;
        }
        long seconds = Math.floorDiv(nanos, 1_000_000_000L);
        long nano = Math.floorMod(nanos, 1_000_000_000L);
        return Instant.ofEpochSecond(seconds, nano).toString();
    }

    private static final class V1 {
        private final String namespace;
        private final String pod;
        private final String container;
        private long chunks;
        private Long from;
        private Long to;

        private V1(String namespace, String pod, String container) {
            this.namespace = namespace;
            this.pod = pod;
            this.container = container;
        }
    }

    private static final class V2 {
        private final String namespace;
        private final String pod;
        private final String container;
        private final Map<String, Long> counts = new LinkedHashMap<>();
        private long eventsSeen;
        private long rowsWritten;
        private Long from;
        private Long to;

        private V2(String namespace, String pod, String container) {
            this.namespace = namespace;
            this.pod = pod;
            this.container = container;
        }
    }
}

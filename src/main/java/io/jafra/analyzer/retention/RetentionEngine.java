package io.jafra.analyzer.retention;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

import io.jafra.analyzer.ingest.decode.IngestCatalog;
import io.jafra.analyzer.storage.ChunkStore;

/** Deletes expired detail, or the whole cache, and records a tombstone. A live instance is left alone. */
public final class RetentionEngine {
    private static final Logger LOG = Logger.getLogger(RetentionEngine.class);

    private final ChunkStore store;
    private final IngestCatalog catalog;
    private final java.nio.file.Path v2;
    private final String mode;
    private final Duration ttl;
    private final boolean clearCache;
    private final Duration interval;

    public RetentionEngine(ChunkStore store, IngestCatalog catalog, java.nio.file.Path v2, String mode, Duration ttl,
            boolean clearCache, Duration interval) {
        this.store = store;
        this.catalog = catalog;
        this.v2 = v2;
        this.mode = mode == null || mode.isBlank() ? "delete-after-48h" : mode;
        this.ttl = ttl;
        this.clearCache = clearCache;
        this.interval = interval;
    }

    public Map<String, Object> status(Instant now) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", mode);
        body.put("raw_ttl_h", ttl.toHours());
        body.put("clear_parquet_cache", clearCache);
        body.put("next_sweep_at", now.plus(interval).toString());
        return body;
    }

    public List<Map<String, Object>> tombstones(Instant since) {
        long from = since.toEpochMilli();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RetentionLog.Entry entry : RetentionLog.read(v2)) {
            if (!"tombstoned".equals(entry.state) && !"deleted".equals(entry.state)) {
                continue;
            }
            if (entry.at < from) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ns", entry.ns);
            row.put("pod", entry.pod);
            row.put("container", entry.container);
            row.put("jvm_instance_id", entry.jvm_instance_id);
            row.put("state", entry.state);
            row.put("at", Instant.ofEpochMilli(entry.at).toString());
            rows.add(row);
        }
        return rows;
    }

    public int sweep(Instant now) throws Exception {
        if ("never".equals(mode)) {
            return 0;
        }
        int changed = 0;
        for (IngestCatalog.ListedInstance instance : catalog.instances()) {
            if (instance.lastTNs() <= 0L) {
                continue;
            }
            long age = now.toEpochMilli() * 1_000_000L - instance.lastTNs();
            if (age <= RetentionFiles.LIVE_NS || age < ttl.toNanos()) {
                continue;
            }
            String state = RetentionLog.state(v2, instance.namespace(), instance.pod(), instance.container(), instance.id());
            if ("deleted".equals(state) || "tombstoned".equals(state)) {
                continue;
            }
            if ("summary_only".equals(state) && !clearCache) {
                continue;
            }
            if (catalog.isDecodingPod(instance.namespace(), instance.pod(), instance.container())) {
                continue;
            }
            apply(clearCache ? "clear-cache" : "purge-raw", instance.namespace(), instance.pod(), instance.container(), instance.id(),
                    false, now);
            changed++;
        }
        return changed;
    }

    public Map<String, Object> apply(String action, String ns, String pod, String container, String instance, boolean dryRun,
            Instant now) throws Exception {
        Map<String, Long> sizes = RetentionFiles.bytes(v2, ns, pod, container, instance);
        long raw = 0L;
        for (String chunkId : catalog.chunkIds(instance)) {
            raw += store.chunkBytes(chunkId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dry_run", dryRun);
        body.put("action", action);
        body.put("ns", ns);
        body.put("pod", pod);
        body.put("container", container);
        body.put("jvm_instance_id", instance);
        body.put("bytes", sizes);
        body.put("raw_bytes", raw);
        if (dryRun) {
            return body;
        }
        if (catalog.isDecodingPod(ns, pod, container)) {
            body.put("skipped", "decoding");
            return body;
        }
        long removed = raw;
        String state = switch (action) {
            case "delete" -> {
                removed += RetentionFiles.deleteAll(v2, ns, pod, container, instance);
                deleteChunks(instance);
                yield "deleted";
            }
            case "clear-cache" -> {
                removed += RetentionFiles.deleteDetail(v2, ns, pod, container, instance);
                removed += RetentionFiles.deleteKeptExceptIdentity(v2, ns, pod, container, instance);
                deleteChunks(instance);
                yield "tombstoned";
            }
            case "purge-raw" -> {
                removed += RetentionFiles.deleteDetail(v2, ns, pod, container, instance);
                deleteChunks(instance);
                yield "summary_only";
            }
            default -> throw new IllegalArgumentException("unknown retention action " + action);
        };
        catalog.markRawPurged(instance);
        long at = now.toEpochMilli();
        Map<String, Long> after = RetentionFiles.bytes(v2, ns, pod, container, instance);
        RetentionLog.upsert(v2, new RetentionLog.Entry(ns, pod, container, instance, state, at, at, 0L,
                RetentionFiles.detailBytes(after), RetentionFiles.keptBytes(after)));
        LOG.infof("retention %s %s/%s/%s/%s removed %d bytes", action, ns, pod, container, instance, removed);
        body.put("state", state);
        body.put("removed_bytes", removed);
        return body;
    }

    private void deleteChunks(String instance) throws Exception {
        for (String chunkId : catalog.chunkIds(instance)) {
            store.deleteChunk(chunkId);
        }
    }
}

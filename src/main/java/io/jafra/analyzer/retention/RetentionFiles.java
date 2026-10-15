package io.jafra.analyzer.retention;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.jafra.analyzer.ingest.decode.ParquetTables;

/** Parquet trees for one JVM instance, split into detail that expires and the summary that stays. */
public final class RetentionFiles {
    public static final List<String> DETAIL = List.of(
            "sample", "duration_event", "gauge", "counter_delta", "rollup_1s", "g1_region_frame", "gc_phase");
    public static final List<String> KEPT = List.of(
            "jvm_instance", "active_setting", "signal", "rollup_10s", "rollup_1m", "marker", "finding", "gc", "top_1m",
            "stack", "frame", "heap_gc");
    public static final long LIVE_NS = 120_000_000_000L;
    public static final String REASON = "retention_48h";

    private RetentionFiles() {}

    public static Path instanceDir(Path v2, String table, String ns, String pod, String container, String instance) {
        return v2.resolve(table)
                .resolve("namespace=" + ParquetTables.sanitize(ns))
                .resolve("pod=" + ParquetTables.sanitize(pod))
                .resolve("container=" + ParquetTables.sanitize(container))
                .resolve("jvm_instance_id=" + ParquetTables.sanitize(instance));
    }

    public static Map<String, Long> bytes(Path v2, String ns, String pod, String container, String instance) {
        Map<String, Long> sizes = new LinkedHashMap<>();
        for (String table : DETAIL) {
            sizes.put(table, size(instanceDir(v2, table, ns, pod, container, instance)));
        }
        for (String table : KEPT) {
            sizes.put(table, size(instanceDir(v2, table, ns, pod, container, instance)));
        }
        return sizes;
    }

    public static long detailBytes(Map<String, Long> sizes) {
        return sum(sizes, DETAIL);
    }

    public static long keptBytes(Map<String, Long> sizes) {
        return sum(sizes, KEPT);
    }

    public static long deleteDetail(Path v2, String ns, String pod, String container, String instance) throws IOException {
        long bytes = 0L;
        for (String table : DETAIL) {
            bytes += deleteTree(instanceDir(v2, table, ns, pod, container, instance));
        }
        return bytes;
    }

    public static long deleteKeptExceptIdentity(Path v2, String ns, String pod, String container, String instance) throws IOException {
        long bytes = 0L;
        for (String table : KEPT) {
            if ("jvm_instance".equals(table)) {
                continue;
            }
            bytes += deleteTree(instanceDir(v2, table, ns, pod, container, instance));
        }
        return bytes;
    }

    public static long deleteAll(Path v2, String ns, String pod, String container, String instance) throws IOException {
        return deleteDetail(v2, ns, pod, container, instance) + deleteTree(instanceDir(v2, "jvm_instance", ns, pod, container, instance))
                + deleteKeptExceptIdentity(v2, ns, pod, container, instance);
    }

    public static long size(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        long bytes = 0L;
        try (var walk = Files.walk(dir)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                bytes += Files.size(path);
            }
        } catch (IOException error) {
            return bytes;
        }
        return bytes;
    }

    public static long deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return 0L;
        }
        long bytes = size(dir);
        try (var walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
        return bytes;
    }

    private static long sum(Map<String, Long> sizes, List<String> tables) {
        long total = 0L;
        for (String table : tables) {
            total += sizes.getOrDefault(table, 0L);
        }
        return total;
    }
}

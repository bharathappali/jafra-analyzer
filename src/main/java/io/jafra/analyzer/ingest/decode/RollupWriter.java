package io.jafra.analyzer.ingest.decode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.jafra.analyzer.metrics.MetricRegistry;

/**
 * Recomputes 1s, 10s, and 1m rollups for the minutes a chunk touched. Every resolution, including
 * 1m duration percentiles, is aggregated from the raw Parquet rows with DuckDB {@code quantile_disc}.
 */
public final class RollupWriter {
    static final long SECOND_NS = 1_000_000_000L;
    static final long TEN_SECONDS_NS = 10_000_000_000L;
    static final long MINUTE_NS = 60_000_000_000L;
    private static final List<String> COLUMNS = List.of(
            "namespace", "pod", "container", "jvm_instance_id", "t", "metric", "bucket_t", "n", "sum", "min", "max", "p50", "p90", "p99", "last");

    private RollupWriter() {}

    public static void refresh(Connection connection, Path v2Root, String namespace, String pod, String container, String instanceId,
            long tStart, long tEnd) throws Exception {
        long from = floor(tStart, MINUTE_NS);
        long last = tEnd > tStart ? tEnd - 1 : tStart;
        long to = floor(last, MINUTE_NS) + MINUTE_NS;
        Instance instance = new Instance(namespace, pod, container, instanceId);
        for (long step : List.of(SECOND_NS, TEN_SECONDS_NS, MINUTE_NS)) {
            Map<Long, List<List<Object>>> buckets = new LinkedHashMap<>();
            collect(connection, buckets, instance, glob(v2Root, "gauge", instance), "value", "metric", null, step, from, to);
            collect(connection, buckets, instance, glob(v2Root, "counter_delta", instance), "delta", "metric", null, step, from, to);
            collectDurations(connection, buckets, instance, glob(v2Root, "duration_event", instance), step, from, to);
            collect(connection, buckets, instance, glob(v2Root, "gc_phase", instance), "dur_ms", null, "gc.pause_ms", step, from, to,
                    " AND level = 0");
            collect(connection, buckets, instance, glob(v2Root, "sample", instance), "weight", null, null, step, from, to, "", true);
            writeBuckets(connection, v2Root, "rollup_" + suffix(step), instance, buckets);
        }
        TopRanks.refresh(connection, v2Root, instance, from, to);
    }

    private static void collect(Connection connection, Map<Long, List<List<Object>>> buckets, Instance instance, String glob,
            String valueColumn, String metricColumn, String constantMetric, long step, long from, long to) throws SQLException {
        collect(connection, buckets, instance, glob, valueColumn, metricColumn, constantMetric, step, from, to, "", false);
    }

    private static void collect(Connection connection, Map<Long, List<List<Object>>> buckets, Instance instance, String glob,
            String valueColumn, String metricColumn, String constantMetric, long step, long from, long to, String extraWhere)
            throws SQLException {
        collect(connection, buckets, instance, glob, valueColumn, metricColumn, constantMetric, step, from, to, extraWhere, false);
    }

    private static void collect(Connection connection, Map<Long, List<List<Object>>> buckets, Instance instance, String glob,
            String valueColumn, String metricColumn, String constantMetric, long step, long from, long to, String extraWhere,
            boolean sampleKind) throws SQLException {
        if (glob == null) {
            return;
        }
        String metricExpr = sampleKind ? "'sample.' || kind" : metricColumn == null ? "'" + constantMetric + "'" : metricColumn;
        String groupMetric = sampleKind || metricColumn != null ? ", 2" : "";
        String sql = "SELECT (t // " + step + ") * " + step + " AS bucket_t, " + metricExpr + " AS metric, "
                + "count(" + valueColumn + ") AS n, sum(" + valueColumn + ") AS sum_v, min(" + valueColumn + ") AS min_v, "
                + "max(" + valueColumn + ") AS max_v, quantile_disc(" + valueColumn + ", 0.5) AS p50, "
                + "quantile_disc(" + valueColumn + ", 0.9) AS p90, quantile_disc(" + valueColumn + ", 0.99) AS p99, "
                + "arg_max(" + valueColumn + ", t) AS last_v "
                + "FROM read_parquet('" + glob + "', hive_partitioning=false) "
                + "WHERE t >= " + from + " AND t < " + to + extraWhere
                + " GROUP BY 1" + groupMetric;
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                long bucket = rows.getLong(1);
                add(buckets, instance, bucket, rows.getString(2), rows.getLong(3), rows.getDouble(4), rows.getDouble(5),
                        rows.getDouble(6), rows.getDouble(7), rows.getDouble(8), rows.getDouble(9), rows.getDouble(10));
            }
        }
    }

    private static void collectDurations(Connection connection, Map<Long, List<List<Object>>> buckets, Instance instance, String glob,
            long step, long from, long to) throws SQLException {
        if (glob == null) {
            return;
        }
        String sql = "SELECT (t // " + step + ") * " + step + " AS bucket_t, type, count(dur_ms), sum(dur_ms), min(dur_ms), "
                + "max(dur_ms), quantile_disc(dur_ms, 0.5), quantile_disc(dur_ms, 0.9), quantile_disc(dur_ms, 0.99), "
                + "arg_max(dur_ms, t) FROM read_parquet('" + glob + "', hive_partitioning=false) "
                + "WHERE t >= " + from + " AND t < " + to
                + " AND type NOT IN ('jdk.JavaExceptionThrow', 'jdk.JavaErrorThrow') GROUP BY 1, 2";
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                String metric = MetricRegistry.durationMetric(rows.getString(2));
                if (metric == null) {
                    continue;
                }
                add(buckets, instance, rows.getLong(1), metric, rows.getLong(3), rows.getDouble(4), rows.getDouble(5),
                        rows.getDouble(6), rows.getDouble(7), rows.getDouble(8), rows.getDouble(9), rows.getDouble(10));
            }
        }
    }

    private static void add(Map<Long, List<List<Object>>> buckets, Instance instance, long bucket, String metric, long n, double sum,
            double min, double max, double p50, double p90, double p99, double last) {
        List<Object> row = new ArrayList<>();
        row.add(instance.namespace());
        row.add(instance.pod());
        row.add(instance.container());
        row.add(instance.id());
        row.add(bucket);
        row.add(metric);
        row.add(bucket);
        row.add(n);
        row.add(sum);
        row.add(min);
        row.add(max);
        row.add(p50);
        row.add(p90);
        row.add(p99);
        row.add(last);
        buckets.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(row);
    }

    private static void writeBuckets(Connection connection, Path v2Root, String table, Instance instance, Map<Long, List<List<Object>>> buckets)
            throws Exception {
        for (var entry : buckets.entrySet()) {
            Path file = ParquetTables.partitionFile(v2Root, table, instance.namespace(), instance.pod(), instance.container(),
                    instance.id(), entry.getKey(), "bucket-" + entry.getKey());
            ParquetTables.write(connection, file, table, COLUMNS, entry.getValue());
        }
    }

    static String glob(Path v2Root, String table, Instance instance) throws SQLException {
        try {
            Path dir = v2Root.resolve(table)
                    .resolve("namespace=" + ParquetTables.sanitize(instance.namespace()))
                    .resolve("pod=" + ParquetTables.sanitize(instance.pod()))
                    .resolve("container=" + ParquetTables.sanitize(instance.container()))
                    .resolve("jvm_instance_id=" + ParquetTables.sanitize(instance.id()));
            if (!Files.isDirectory(dir)) {
                return null;
            }
            try (var walk = Files.walk(dir)) {
                if (walk.noneMatch(path -> path.getFileName().toString().endsWith(".parquet"))) {
                    return null;
                }
            }
            return dir.toAbsolutePath().toString().replace("'", "''") + "/**/*.parquet";
        } catch (java.io.IOException error) {
            throw new SQLException("cannot read " + table + " partition", error);
        }
    }

    private static String suffix(long step) {
        if (step == SECOND_NS) {
            return "1s";
        }
        if (step == TEN_SECONDS_NS) {
            return "10s";
        }
        return "1m";
    }

    private static long floor(long t, long step) {
        return Math.floorDiv(t, step) * step;
    }

    record Instance(String namespace, String pod, String container, String id) {}
}

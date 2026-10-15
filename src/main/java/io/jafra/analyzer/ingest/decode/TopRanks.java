package io.jafra.analyzer.ingest.decode;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Top 50 keys per minute for each kind and group. Built at ingest so retention can drop raw samples. */
final class TopRanks {
    private static final List<String> COLUMNS = List.of(
            "namespace", "pod", "container", "jvm_instance_id", "t", "kind", "grp", "key", "stack_id", "weight", "n");
    private static final long MINUTE = RollupWriter.MINUTE_NS;

    private TopRanks() {}

    static void refresh(Connection connection, Path v2Root, RollupWriter.Instance instance, long from, long to) throws Exception {
        String samples = RollupWriter.glob(v2Root, "sample", instance);
        String stacks = RollupWriter.glob(v2Root, "stack", instance);
        String frames = RollupWriter.glob(v2Root, "frame", instance);
        String durations = RollupWriter.glob(v2Root, "duration_event", instance);
        Map<Long, List<List<Object>>> buckets = new LinkedHashMap<>();
        if (samples != null && stacks != null && frames != null) {
            read(connection, buckets, instance, sampleRank(samples, stacks, frames, from, to, "method", "f.method"));
            read(connection, buckets, instance, sampleRank(samples, stacks, frames, from, to, "class",
                    "CASE WHEN s.kind = 'alloc' AND s.object_class IS NOT NULL AND s.object_class <> '' THEN s.object_class ELSE f.class_name END"));
        }
        if (durations != null) {
            read(connection, buckets, instance, durationRank(durations, from, to, "lock", "monitor",
                    "coalesce(nullif(monitor_class, ''), nullif(parked_class, ''))",
                    "type IN ('jdk.JavaMonitorEnter', 'jdk.JavaMonitorWait', 'jdk.ThreadPark', 'profiler.NativeLock')"));
            read(connection, buckets, instance, durationRank(durations, from, to, "io", "target", "target",
                    "type IN ('jdk.FileRead', 'jdk.FileWrite', 'jdk.FileForce', 'jdk.SocketRead', 'jdk.SocketWrite')"));
            read(connection, buckets, instance, durationRank(durations, from, to, "exceptions", "class", "target",
                    "type IN ('jdk.JavaExceptionThrow', 'jdk.JavaErrorThrow')"));
        }
        if (durations != null && stacks != null && frames != null) {
            read(connection, buckets, instance, durationMethod(durations, stacks, frames, from, to, "io",
                    "type IN ('jdk.FileRead', 'jdk.FileWrite', 'jdk.FileForce', 'jdk.SocketRead', 'jdk.SocketWrite')"));
            read(connection, buckets, instance, durationMethod(durations, stacks, frames, from, to, "exceptions",
                    "type IN ('jdk.JavaExceptionThrow', 'jdk.JavaErrorThrow')"));
            read(connection, buckets, instance, durationMethod(durations, stacks, frames, from, to, "lock",
                    "type IN ('jdk.JavaMonitorEnter', 'jdk.JavaMonitorWait', 'jdk.ThreadPark', 'profiler.NativeLock')"));
        }
        for (var entry : buckets.entrySet()) {
            var file = ParquetTables.partitionFile(v2Root, "top_1m", instance.namespace(), instance.pod(), instance.container(),
                    instance.id(), entry.getKey(), "top-" + entry.getKey());
            ParquetTables.write(connection, file, "top_1m", COLUMNS, entry.getValue());
        }
    }

    private static void read(Connection connection, Map<Long, List<List<Object>>> buckets, RollupWriter.Instance instance, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                long bucket = rows.getLong(1);
                List<Object> row = new ArrayList<>();
                row.add(instance.namespace());
                row.add(instance.pod());
                row.add(instance.container());
                row.add(instance.id());
                row.add(bucket);
                row.add(rows.getString(2));
                row.add(rows.getString(3));
                row.add(rows.getString(4));
                row.add(rows.getString(5));
                row.add(rows.getDouble(6));
                row.add(rows.getLong(7));
                buckets.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(row);
            }
        }
    }

    private static String sampleRank(String samples, String stacks, String frames, long from, long to, String group, String keyExpr) {
        return "WITH frames AS (SELECT frame_id, any_value(\"class\") AS class_name, any_value(method) AS method "
                + "FROM read_parquet('" + frames + "', hive_partitioning=false) GROUP BY frame_id), "
                + "stacks AS (SELECT stack_id, any_value(leaf_frame_id) AS leaf_frame_id "
                + "FROM read_parquet('" + stacks + "', hive_partitioning=false) GROUP BY stack_id), "
                + "weighted AS (SELECT (s.t // " + MINUTE + ") * " + MINUTE + " AS bucket_t, "
                + "CASE WHEN s.kind = 'native_lock' THEN 'lock' ELSE s.kind END AS kind, " + keyExpr + " AS key, s.stack_id, "
                + "sum(s.weight) AS weight, count(*) AS n "
                + "FROM read_parquet('" + samples + "', hive_partitioning=false) s "
                + "JOIN stacks st ON s.stack_id = st.stack_id JOIN frames f ON st.leaf_frame_id = f.frame_id "
                + "WHERE s.t >= " + from + " AND s.t < " + to + " AND s.kind IN ('cpu', 'wall', 'alloc', 'lock', 'native_lock') "
                + "GROUP BY 1, 2, 3, 4), "
                + "rolled AS (SELECT bucket_t, kind, '" + group + "' AS grp, key, arg_max(stack_id, weight) AS stack_id, "
                + "sum(weight) AS weight, sum(n) AS n FROM weighted WHERE key IS NOT NULL AND key <> '' GROUP BY 1, 2, 3, 4) "
                + "SELECT bucket_t, kind, grp, key, stack_id, weight, n FROM ("
                + "SELECT *, row_number() OVER (PARTITION BY bucket_t, kind, grp ORDER BY weight DESC, key) AS rn FROM rolled"
                + ") ranked WHERE rn <= 50";
    }

    private static String durationRank(String durations, long from, long to, String kind, String group, String keyExpr, String filter) {
        return "WITH rolled AS (SELECT (t // " + MINUTE + ") * " + MINUTE + " AS bucket_t, '" + kind + "' AS kind, '"
                + group + "' AS grp, " + keyExpr + " AS key, NULL AS stack_id, sum(CASE WHEN dur_ms > 0 THEN dur_ms ELSE 1 END) AS weight, "
                + "count(*) AS n FROM read_parquet('" + durations + "', hive_partitioning=false) WHERE t >= " + from + " AND t < " + to
                + " AND " + filter + " GROUP BY 1, 4) "
                + "SELECT bucket_t, kind, grp, key, stack_id, weight, n FROM ("
                + "SELECT *, row_number() OVER (PARTITION BY bucket_t, kind, grp ORDER BY weight DESC, key) AS rn FROM rolled "
                + "WHERE key IS NOT NULL AND key <> '') ranked WHERE rn <= 50";
    }

    private static String durationMethod(String durations, String stacks, String frames, long from, long to, String kind, String filter) {
        return "WITH frames AS (SELECT frame_id, any_value(method) AS method FROM read_parquet('" + frames
                + "', hive_partitioning=false) GROUP BY frame_id), "
                + "stacks AS (SELECT stack_id, any_value(leaf_frame_id) AS leaf_frame_id FROM read_parquet('" + stacks
                + "', hive_partitioning=false) GROUP BY stack_id), "
                + "rolled AS (SELECT (d.t // " + MINUTE + ") * " + MINUTE + " AS bucket_t, '" + kind + "' AS kind, 'method' AS grp, "
                + "f.method AS key, arg_max(d.stack_id, CASE WHEN d.dur_ms > 0 THEN d.dur_ms ELSE 1 END) AS stack_id, "
                + "sum(CASE WHEN d.dur_ms > 0 THEN d.dur_ms ELSE 1 END) AS weight, count(*) AS n "
                + "FROM read_parquet('" + durations + "', hive_partitioning=false) d "
                + "JOIN stacks st ON d.stack_id = st.stack_id JOIN frames f ON st.leaf_frame_id = f.frame_id "
                + "WHERE d.t >= " + from + " AND d.t < " + to + " AND " + filter
                + " GROUP BY 1, 4) "
                + "SELECT bucket_t, kind, grp, key, stack_id, weight, n FROM ("
                + "SELECT *, row_number() OVER (PARTITION BY bucket_t, kind, grp ORDER BY weight DESC, key) AS rn FROM rolled "
                + "WHERE key IS NOT NULL AND key <> '') ranked WHERE rn <= 50";
    }
}

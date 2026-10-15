package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class RollupPercentileTest {
    @Test
    void minutePercentilesMatchQuantileDiscOnRawRows() throws Exception {
        Path root = Files.createTempDirectory("jafra-rollup");
        Path v2 = root.resolve("v2");
        long minute = Instant.parse("2026-09-12T12:00:00Z").getEpochSecond() * 1_000_000_000L;
        try (IngestCatalog catalog = new IngestCatalog(v2)) {
            List<List<Object>> gauges = new ArrayList<>();
            for (int value = 1; value <= 10; value++) {
                gauges.add(List.of("default", "pod", "app", "instance", minute + value * 1_000_000_000L, "cpu.jvm_user", (double) value));
            }
            Path gauge = ParquetTables.partitionFile(v2, "gauge", "default", "pod", "app", "instance", minute, "chunk-g");
            ParquetTables.write(catalog.connection(), gauge, "gauge",
                    List.of("namespace", "pod", "container", "jvm_instance_id", "t", "metric", "value"), gauges);

            List<List<Object>> durations = List.of(
                    duration(minute, 1.0),
                    duration(minute + 5_000_000_000L, 100.0));
            Path duration = ParquetTables.partitionFile(v2, "duration_event", "default", "pod", "app", "instance", minute, "chunk-d");
            ParquetTables.write(catalog.connection(), duration, "duration_event", List.of(
                    "namespace", "pod", "container", "jvm_instance_id", "t", "type", "dur_ms", "os_thread_id", "thread_group", "stack_id",
                    "target", "monitor_class", "parked_class", "previous_owner", "bytes", "op_name", "safepoint_id", "extra"), durations);

            RollupWriter.refresh(catalog.connection(), v2, "default", "pod", "app", "instance", minute, minute + 6_000_000_000L);

            double rawGauge = quantile(v2.resolve("gauge"), "value", 0.99);
            double rollupGauge = rollup(v2.resolve("rollup_1m"), "cpu.jvm_user", "p99");
            assertEquals(rawGauge, rollupGauge, 0.0);

            double rawDuration = quantile(v2.resolve("duration_event"), "dur_ms", 0.99);
            double rollupDuration = rollup(v2.resolve("rollup_1m"), "io.socket_read_ms", "p99");
            assertEquals(rawDuration, rollupDuration, 0.0);

            double firstSecond = rollup(v2.resolve("rollup_1s"), "io.socket_read_ms", "p99");
            double laterSecond = quantileAt(v2.resolve("duration_event"), "dur_ms", 0.99, minute + 5_000_000_000L);
            double merged = (firstSecond + laterSecond) / 2.0;
            assertNotEquals(merged, rollupDuration, 0.0);
        }
    }

    private static List<Object> duration(long t, double durMs) {
        List<Object> row = new ArrayList<>();
        row.add("default");
        row.add("pod");
        row.add("app");
        row.add("instance");
        row.add(t);
        row.add("jdk.SocketRead");
        row.add(durMs);
        row.add(1L);
        row.add("main");
        row.add(null);
        row.add("db:5432");
        row.add(null);
        row.add(null);
        row.add(null);
        row.add(10L);
        row.add(null);
        row.add(null);
        row.add(null);
        return row;
    }

    private static double quantile(Path table, String column, double q) throws Exception {
        return query(table, "SELECT quantile_disc(" + column + ", " + q + ") FROM read_parquet('" + glob(table) + "', hive_partitioning=false)");
    }

    private static double quantileAt(Path table, String column, double q, long t) throws Exception {
        return query(table, "SELECT quantile_disc(" + column + ", " + q + ") FROM read_parquet('" + glob(table)
                + "', hive_partitioning=false) WHERE t = " + t);
    }

    private static double rollup(Path table, String metric, String column) throws Exception {
        return query(table, "SELECT " + column + " FROM read_parquet('" + glob(table) + "', hive_partitioning=false) WHERE metric = '"
                + metric + "'");
    }

    private static double query(Path table, String sql) throws Exception {
        IngestCatalog.ensureDriver();
        try (var connection = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getDouble(1);
        }
    }

    private static String glob(Path table) {
        return table.toAbsolutePath().toString().replace("'", "''") + "/**/*.parquet";
    }
}

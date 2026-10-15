package io.jafra.analyzer.ingest.decode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** Writes one zstd Parquet file for a chunk under the hive partition layout. */
public final class ParquetTables {
    private ParquetTables() {}

    public static Path partitionFile(
            Path v2Root, String table, String namespace, String pod, String container, String jvmInstanceId, long tNs, String chunkId)
            throws java.io.IOException {
        String date = Instant.ofEpochSecond(Math.max(0L, tNs) / 1_000_000_000L)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .toString();
        Path dir = v2Root.resolve(table)
                .resolve("namespace=" + sanitize(namespace))
                .resolve("pod=" + sanitize(pod))
                .resolve("container=" + sanitize(container))
                .resolve("jvm_instance_id=" + sanitize(jvmInstanceId))
                .resolve("date=" + date);
        Files.createDirectories(dir);
        return dir.resolve(chunkId + ".parquet");
    }

    public static void write(Connection connection, Path file, String table, List<String> columns, List<List<Object>> rows)
            throws SQLException {
        String temp = "batch_" + table;
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + temp);
            statement.execute("CREATE TEMP TABLE " + temp + " (" + declared(table, columns) + ")");
        }
        if (!rows.isEmpty()) {
            String placeholders = "?,".repeat(columns.size());
            placeholders = placeholders.substring(0, placeholders.length() - 1);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + temp + " VALUES (" + placeholders + ")")) {
                for (List<Object> row : rows) {
                    for (int i = 0; i < columns.size(); i++) {
                        insert.setObject(i + 1, row.get(i));
                    }
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
        String path = file.toAbsolutePath().toString().replace("'", "''");
        try (Statement statement = connection.createStatement()) {
            statement.execute("COPY " + temp + " TO '" + path + "' (FORMAT parquet, COMPRESSION zstd)");
            statement.execute("DROP TABLE IF EXISTS " + temp);
        }
    }

    private static String declared(String table, List<String> columns) {
        StringBuilder builder = new StringBuilder();
        for (String column : columns) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append('"').append(column).append("\" ").append(sqlType(table, column));
        }
        return builder.toString();
    }

    private static String sqlType(String table, String column) {
        if ("active_setting".equals(table) && "value".equals(column)) {
            return "VARCHAR";
        }
        return switch (column) {
            case "t", "t_end", "start_t", "jvm_start", "pid", "gc_id", "instance_seq", "level",
                    "cpu_quota", "cpu_slice_period", "cpu_shares", "mem_limit", "effective_cpus", "jdk_feature",
                    "os_thread_id", "size", "bytes", "line", "depth", "safepoint_id" -> "BIGINT";
            case "value", "dt_s", "delta", "sum_pause_ms", "longest_pause_ms", "dur_ms", "magnitude", "weight" -> "DOUBLE";
            case "provisional", "concurrent" -> "BOOLEAN";
            default -> "VARCHAR";
        };
    }

    static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "_";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}

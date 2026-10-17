package io.jafra.analyzer.ingest.decode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

/** Writes one zstd Parquet file for a chunk under the hive partition layout. */
public final class ParquetTables {
    /** macOS F_FULLFSYNC flushes the drive, so one flush at the end of a chunk covers every file just renamed. */
    private static final boolean APPLE = System.getProperty("os.name", "").regionMatches(true, 0, "Mac", 0, 3);

    /** Test hook after the temp file is durable and before it replaces the chunk file. */
    public static volatile Consumer<Path> beforeRename;

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
        write(connection, file, table, columns, rows, true);
    }

    /**
     * @param deviceSync false leaves the drive flush to {@link #flushDevice}. On macOS that one flush covers the
     *     chunk. Elsewhere each file and its directory are synced here.
     */
    static void write(Connection connection, Path file, String table, List<String> columns, List<List<Object>> rows,
            boolean deviceSync) throws SQLException {
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
        Path tempFile = file.resolveSibling(file.getFileName().toString() + ".tmp");
        String path = tempFile.toAbsolutePath().toString().replace("'", "''");
        try (Statement statement = connection.createStatement()) {
            try {
                statement.execute("COPY " + temp + " TO '" + path + "' (FORMAT parquet, COMPRESSION zstd)");
            } finally {
                statement.execute("DROP TABLE IF EXISTS " + temp);
            }
        }
        boolean syncFile = deviceSync || !APPLE;
        if (syncFile) {
            try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.WRITE)) {
                channel.force(deviceSync);
            } catch (IOException error) {
                deleteQuietly(tempFile);
                throw new SQLException("could not fsync " + tempFile, error);
            }
        }
        Consumer<Path> hook = beforeRename;
        if (hook != null) {
            hook.accept(tempFile);
        }
        try {
            Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (syncFile) {
                fsyncDirectory(file.getParent(), deviceSync);
            }
        } catch (IOException error) {
            deleteQuietly(tempFile);
            throw new SQLException("could not publish " + file, error);
        }
    }

    /** One drive flush after a chunk's files have been renamed. A crash before this leaves the chunk uncommitted. */
    static void flushDevice(Path file) throws SQLException {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        } catch (IOException error) {
            throw new SQLException("could not flush " + file, error);
        }
        try {
            fsyncDirectory(file.getParent(), true);
        } catch (IOException error) {
            throw new SQLException("could not flush " + file.getParent(), error);
        }
    }

    private static void fsyncDirectory(Path directory, boolean deviceSync) throws IOException {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(deviceSync);
        } catch (IOException error) {
            if (error instanceof java.nio.file.AccessDeniedException || error.getMessage() != null && error.getMessage().contains("Is a directory")) {
                return;
            }
            throw error;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // the next retry overwrites the same temp name
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
            case "t", "t_end", "start_t", "bucket_t", "jvm_start", "pid", "gc_id", "instance_seq", "level",
                    "cpu_quota", "cpu_slice_period", "cpu_shares", "mem_limit", "effective_cpus", "jdk_feature",
                    "os_thread_id", "size", "bytes", "line", "depth", "safepoint_id", "n", "min_jdk",
                    "cpu_request_millicores_approx", "heap_before", "heap_after", "committed", "eden_before", "eden_after",
                    "survivor_before", "survivor_after", "old_before", "old_after", "young_capacity", "humongous_regions",
                    "promoted_bytes", "tenuring_threshold", "region_count", "used", "max_heap_bytes" -> "BIGINT";
            case "value", "dt_s", "delta", "sum_pause_ms", "longest_pause_ms", "dur_ms", "magnitude", "weight",
                    "sum", "min", "max", "p50", "p90", "p99", "last", "cpu_limit_cores" -> "DOUBLE";
            case "provisional", "concurrent", "estimated" -> "BOOLEAN";
            default -> "VARCHAR";
        };
    }

    public static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "_";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}

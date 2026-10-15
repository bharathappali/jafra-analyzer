package io.jafra.analyzer.ingest.decode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** DuckDB catalog for chunk_ingest, instance sequence, and counter cursors. */
public final class IngestCatalog implements AutoCloseable {
    private final Connection connection;

    public IngestCatalog(Path v2Root) throws SQLException {
        try {
            Files.createDirectories(v2Root);
            ensureDriver();
            connection = DriverManager.getConnection("jdbc:duckdb:" + v2Root.resolve("catalog.duckdb"));
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS chunk_ingest (
                          chunk_id VARCHAR PRIMARY KEY,
                          jvm_instance_id VARCHAR,
                          recording_id VARCHAR,
                          t_start BIGINT,
                          t_end BIGINT,
                          source VARCHAR,
                          status VARCHAR,
                          rows BIGINT,
                          error VARCHAR
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS jvm_instance_state (
                          jvm_instance_id VARCHAR PRIMARY KEY,
                          namespace VARCHAR,
                          pod VARCHAR,
                          container VARCHAR,
                          recording_id VARCHAR,
                          jvm_start_ms BIGINT,
                          instance_seq INTEGER,
                          provisional BOOLEAN,
                          restart_reason_hint VARCHAR,
                          last_t_ns BIGINT
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS counter_cursor (
                          jvm_instance_id VARCHAR,
                          metric VARCHAR,
                          series VARCHAR,
                          last_t_ns BIGINT,
                          last_value DOUBLE,
                          PRIMARY KEY (jvm_instance_id, metric, series)
                        )
                        """);
            }
        } catch (java.io.IOException error) {
            throw new SQLException("cannot create v2 catalog directory", error);
        }
    }

    public synchronized boolean isIngested(String chunkId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM chunk_ingest WHERE chunk_id = ?")) {
            statement.setString(1, chunkId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && "ok".equals(rows.getString(1));
            }
        }
    }

    public synchronized void record(String chunkId, String jvmInstanceId, String recordingId, long tStart, long tEnd,
            String status, long rows, String error) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM chunk_ingest WHERE chunk_id = ?")) {
            delete.setString(1, chunkId);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                """
                INSERT INTO chunk_ingest
                  (chunk_id, jvm_instance_id, recording_id, t_start, t_end, source, status, rows, error)
                VALUES (?, ?, ?, ?, ?, 'jfr', ?, ?, ?)
                """)) {
            insert.setString(1, chunkId);
            insert.setString(2, jvmInstanceId);
            insert.setString(3, recordingId);
            insert.setLong(4, tStart);
            insert.setLong(5, tEnd);
            insert.setString(6, status);
            insert.setLong(7, rows);
            insert.setString(8, error);
            insert.executeUpdate();
        }
    }

    public synchronized String inheritInstance(String recordingId, long chunkStartNs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT jvm_instance_id FROM chunk_ingest
                WHERE recording_id = ? AND status = 'ok' AND t_end <= ?
                ORDER BY t_end DESC LIMIT 1
                """)) {
            statement.setString(1, recordingId);
            statement.setLong(2, chunkStartNs);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    public synchronized InstanceState ensureInstance(
            String jvmInstanceId,
            String namespace,
            String pod,
            String container,
            String recordingId,
            long jvmStartMs,
            boolean provisional) throws SQLException {
        try (PreparedStatement existing = connection.prepareStatement(
                "SELECT instance_seq, restart_reason_hint FROM jvm_instance_state WHERE jvm_instance_id = ?")) {
            existing.setString(1, jvmInstanceId);
            try (ResultSet rows = existing.executeQuery()) {
                if (rows.next()) {
                    return new InstanceState(rows.getInt(1), rows.getString(2), null, null, 0L);
                }
            }
        }
        int seq = 1;
        String previousId = null;
        long previousEnd = 0L;
        String hint = "unknown";
        try (PreparedStatement previous = connection.prepareStatement(
                """
                SELECT jvm_instance_id, last_t_ns, restart_reason_hint, instance_seq
                FROM jvm_instance_state
                WHERE namespace = ? AND pod = ? AND container = ?
                ORDER BY jvm_start_ms DESC LIMIT 1
                """)) {
            previous.setString(1, namespace);
            previous.setString(2, pod);
            previous.setString(3, container);
            try (ResultSet rows = previous.executeQuery()) {
                if (rows.next()) {
                    previousId = rows.getString(1);
                    previousEnd = rows.getLong(2);
                    String previousHint = rows.getString(3);
                    seq = rows.getInt(4) + 1;
                    if (previousHint != null && !previousHint.isBlank()) {
                        hint = previousHint;
                    }
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                """
                INSERT INTO jvm_instance_state
                  (jvm_instance_id, namespace, pod, container, recording_id, jvm_start_ms, instance_seq,
                   provisional, restart_reason_hint, last_t_ns)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, jvmInstanceId);
            insert.setString(2, namespace);
            insert.setString(3, pod);
            insert.setString(4, container);
            insert.setString(5, recordingId);
            insert.setLong(6, jvmStartMs);
            insert.setInt(7, seq);
            insert.setBoolean(8, provisional);
            insert.setString(9, seq == 1 ? "unknown" : hint);
            insert.setLong(10, 0L);
            insert.executeUpdate();
        }
        return new InstanceState(seq, seq == 1 ? "unknown" : hint, previousId, previousId == null ? null : hint, previousEnd);
    }

    public synchronized void touchInstance(String jvmInstanceId, long tNs, String hint) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                UPDATE jvm_instance_state
                SET last_t_ns = CASE WHEN ? > last_t_ns THEN ? ELSE last_t_ns END,
                    restart_reason_hint = COALESCE(?, restart_reason_hint)
                WHERE jvm_instance_id = ?
                """)) {
            statement.setLong(1, tNs);
            statement.setLong(2, tNs);
            statement.setString(3, hint);
            statement.setString(4, jvmInstanceId);
            statement.executeUpdate();
        }
    }

    public synchronized CounterCursor cursor(String jvmInstanceId, String metric, String series) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT last_t_ns, last_value FROM counter_cursor
                WHERE jvm_instance_id = ? AND metric = ? AND series = ?
                """)) {
            statement.setString(1, jvmInstanceId);
            statement.setString(2, metric);
            statement.setString(3, series);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return new CounterCursor(null, null);
                }
                return new CounterCursor(rows.getLong(1), rows.getDouble(2));
            }
        }
    }

    public synchronized void saveCursor(String jvmInstanceId, String metric, String series, long tNs, double value)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM counter_cursor WHERE jvm_instance_id = ? AND metric = ? AND series = ?")) {
            delete.setString(1, jvmInstanceId);
            delete.setString(2, metric);
            delete.setString(3, series);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO counter_cursor (jvm_instance_id, metric, series, last_t_ns, last_value) VALUES (?, ?, ?, ?, ?)")) {
            insert.setString(1, jvmInstanceId);
            insert.setString(2, metric);
            insert.setString(3, series);
            insert.setLong(4, tNs);
            insert.setDouble(5, value);
            insert.executeUpdate();
        }
    }

    public synchronized void rekey(String provisionalId, String realId) throws SQLException {
        try (PreparedStatement ingest = connection.prepareStatement(
                "UPDATE chunk_ingest SET jvm_instance_id = ? WHERE jvm_instance_id = ?")) {
            ingest.setString(1, realId);
            ingest.setString(2, provisionalId);
            ingest.executeUpdate();
        }
    }

    public synchronized int okCount() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM chunk_ingest WHERE status = 'ok'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    static void ensureDriver() throws SQLException {
        try {
            Class.forName("org.duckdb.DuckDBDriver");
        } catch (ClassNotFoundException error) {
            throw new SQLException("duckdb jdbc driver is not on the classpath", error);
        }
    }

    public Connection connection() {
        return connection;
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }

    public record InstanceState(
            int instanceSeq, String restartReasonHint, String previousInstanceId, String previousHint, long previousEndNs) {}

    public record CounterCursor(Long lastTNs, Double lastValue) {}
}

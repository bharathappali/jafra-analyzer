package io.jafra.analyzer.ingest.decode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** DuckDB catalog for chunk_ingest, instance sequence, and counter cursors. */
public final class IngestCatalog implements AutoCloseable {
    /** A following chunk still belongs to the previous JVM when it starts within this slack of that JVM's last end. */
    public static final long CONTINUITY_NS = 15_000_000_000L;
    /** Chunk headers can sit slightly before jdk.JVMInformation's start time. */
    private static final long CLAIM_SLACK_NS = 1_000_000_000L;

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
                statement.execute("ALTER TABLE chunk_ingest ADD COLUMN IF NOT EXISTS raw_purged BOOLEAN");
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

    /** Newest non-provisional instance whose last chunk still abuts this chunk. */
    public synchronized String inheritStableInstance(String namespace, String pod, String container, long chunkStartNs)
            throws SQLException {
        return inheritWorkload(namespace, pod, container, false, chunkStartNs);
    }

    /** Newest provisional instance whose last chunk still abuts this chunk. */
    public synchronized String inheritAnyInstance(String namespace, String pod, String container, long chunkStartNs)
            throws SQLException {
        return inheritWorkload(namespace, pod, container, true, chunkStartNs);
    }

    static boolean continues(long chunkStartNs, long lastTNs) {
        if (lastTNs <= 0L) {
            return false;
        }
        long delta = chunkStartNs - lastTNs;
        return delta >= -CONTINUITY_NS && delta <= CONTINUITY_NS;
    }

    private String inheritWorkload(String namespace, String pod, String container, boolean provisional, long chunkStartNs)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT jvm_instance_id, last_t_ns FROM jvm_instance_state
                WHERE namespace = ? AND pod = ? AND container = ? AND provisional = ?
                ORDER BY instance_seq DESC, last_t_ns DESC
                LIMIT 1
                """)) {
            statement.setString(1, namespace);
            statement.setString(2, pod);
            statement.setString(3, container);
            statement.setBoolean(4, provisional);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !continues(chunkStartNs, rows.getLong(2))) {
                    return null;
                }
                return rows.getString(1);
            }
        }
    }

    /**
     * Chunks on this workload that start with the JVM just identified, and so must leave whatever id they were
     * written under. Earlier chunks of a previous JVM stay where they are.
     */
    public synchronized List<Claim> previewClaims(String namespace, String pod, String container, String realId, long startNs)
            throws SQLException {
        Map<String, ClaimBuilder> groups = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT ci.jvm_instance_id, ci.chunk_id, ci.t_start, ci.t_end, s.provisional
                FROM chunk_ingest ci
                JOIN jvm_instance_state s ON s.jvm_instance_id = ci.jvm_instance_id
                WHERE s.namespace = ? AND s.pod = ? AND s.container = ?
                  AND ci.status = 'ok'
                  AND ci.jvm_instance_id <> ?
                  AND ci.t_start >= ?
                """)) {
            statement.setString(1, namespace);
            statement.setString(2, pod);
            statement.setString(3, container);
            statement.setString(4, realId);
            statement.setLong(5, startNs - CLAIM_SLACK_NS);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ClaimBuilder group = groups.computeIfAbsent(rows.getString(1), ClaimBuilder::new);
                    group.provisional = rows.getBoolean(5);
                    group.chunkIds.add(rows.getString(2));
                    group.minStart = Math.min(group.minStart, rows.getLong(3));
                    group.maxEnd = Math.max(group.maxEnd, rows.getLong(4));
                }
            }
        }
        List<Claim> claims = new ArrayList<>();
        for (ClaimBuilder group : groups.values()) {
            boolean emptied = group.provisional && group.chunkIds.size() == countForInstance(group.id);
            claims.add(new Claim(group.id, group.provisional, emptied, List.copyOf(group.chunkIds), group.minStart, group.maxEnd));
        }
        return claims;
    }

    public synchronized void applyClaims(List<Claim> claims, String realId) throws SQLException {
        for (Claim claim : claims) {
            if (claim.emptied()) {
                rekey(claim.fromId(), realId);
                continue;
            }
            for (String chunkId : claim.chunkIds()) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE chunk_ingest SET jvm_instance_id = ? WHERE chunk_id = ?")) {
                    update.setString(1, realId);
                    update.setString(2, chunkId);
                    update.executeUpdate();
                }
            }
            try (PreparedStatement rewind = connection.prepareStatement(
                    """
                    UPDATE jvm_instance_state
                    SET last_t_ns = COALESCE(
                        (SELECT max(t_end) FROM chunk_ingest WHERE jvm_instance_id = ? AND status = 'ok'), 0)
                    WHERE jvm_instance_id = ?
                    """)) {
                rewind.setString(1, claim.fromId());
                rewind.setString(2, claim.fromId());
                rewind.executeUpdate();
            }
        }
    }

    private int countForInstance(String jvmInstanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM chunk_ingest WHERE jvm_instance_id = ? AND status = 'ok'")) {
            statement.setString(1, jvmInstanceId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
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
                ORDER BY instance_seq DESC, last_t_ns DESC LIMIT 1
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
        if (provisionalId == null || provisionalId.equals(realId)) {
            return;
        }
        try (PreparedStatement ingest = connection.prepareStatement(
                "UPDATE chunk_ingest SET jvm_instance_id = ? WHERE jvm_instance_id = ?")) {
            ingest.setString(1, realId);
            ingest.setString(2, provisionalId);
            ingest.executeUpdate();
        }
        try (PreparedStatement state = connection.prepareStatement(
                "DELETE FROM jvm_instance_state WHERE jvm_instance_id = ? AND provisional = true")) {
            state.setString(1, provisionalId);
            state.executeUpdate();
        }
        try (PreparedStatement cursors = connection.prepareStatement(
                "DELETE FROM counter_cursor WHERE jvm_instance_id = ?")) {
            cursors.setString(1, provisionalId);
            cursors.executeUpdate();
        }
    }

    public synchronized List<ListedInstance> instances() throws SQLException {
        List<ListedInstance> instances = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT jvm_instance_id, namespace, pod, container, last_t_ns FROM jvm_instance_state")) {
            while (rows.next()) {
                instances.add(new ListedInstance(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                        rows.getLong(5)));
            }
        }
        return instances;
    }

    public synchronized List<String> chunkIds(String jvmInstanceId) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT chunk_id FROM chunk_ingest WHERE jvm_instance_id = ?")) {
            statement.setString(1, jvmInstanceId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getString(1));
                }
            }
        }
        return ids;
    }

    public synchronized void markRawPurged(String jvmInstanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE chunk_ingest SET raw_purged = true WHERE jvm_instance_id = ?")) {
            statement.setString(1, jvmInstanceId);
            statement.executeUpdate();
        }
    }

    public synchronized int okCount() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM chunk_ingest WHERE status = 'ok'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    public static void ensureDriver() throws SQLException {
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

    public record ListedInstance(String id, String namespace, String pod, String container, long lastTNs) {}

    public record Claim(
            String fromId, boolean provisional, boolean emptied, List<String> chunkIds, long minStartNs, long maxEndNs) {}

    private static final class ClaimBuilder {
        private final String id;
        private boolean provisional;
        private final List<String> chunkIds = new ArrayList<>();
        private long minStart = Long.MAX_VALUE;
        private long maxEnd = 0L;

        private ClaimBuilder(String id) {
            this.id = id;
        }
    }
}

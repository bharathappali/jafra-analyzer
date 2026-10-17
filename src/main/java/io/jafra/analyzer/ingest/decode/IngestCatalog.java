package io.jafra.analyzer.ingest.decode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import io.jafra.analyzer.storage.ChunkMetadata;

/** DuckDB catalog for chunk_ingest, instance sequence, and counter cursors. */
public final class IngestCatalog implements AutoCloseable {
    /** Failures before the chunk is quarantined and left for an operator. */
    public static final int MAX_ATTEMPTS = 5;
    /** Chunk headers can sit slightly before jdk.JVMInformation's start time. */
    private static final long CLAIM_SLACK_NS = 1_000_000_000L;

    private final Connection connection;
    private final Path v2Root;
    private int lanes = 4;

    public IngestCatalog(Path v2Root) throws SQLException {
        this.v2Root = v2Root;
        try {
            Files.createDirectories(v2Root);
            connection = DuckConnections.open("jdbc:duckdb:" + v2Root.resolve("catalog.duckdb"));
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
                statement.execute("ALTER TABLE chunk_ingest ADD COLUMN IF NOT EXISTS attempts INTEGER DEFAULT 0");
                statement.execute("ALTER TABLE chunk_ingest ADD COLUMN IF NOT EXISTS next_attempt_ns BIGINT");
                statement.execute("ALTER TABLE chunk_ingest ADD COLUMN IF NOT EXISTS events_seen BIGINT");
                statement.execute("ALTER TABLE chunk_ingest ADD COLUMN IF NOT EXISTS rows_written BIGINT");
                statement.execute("ALTER TABLE chunk_ingest ADD COLUMN IF NOT EXISTS namespace VARCHAR");
                statement.execute("ALTER TABLE chunk_ingest ADD COLUMN IF NOT EXISTS pod VARCHAR");
                statement.execute("ALTER TABLE chunk_ingest ADD COLUMN IF NOT EXISTS container VARCHAR");
                statement.execute("ALTER TABLE chunk_ingest ADD COLUMN IF NOT EXISTS pod_uid VARCHAR");
                statement.execute("ALTER TABLE jvm_instance_state ADD COLUMN IF NOT EXISTS pod_uid VARCHAR");
                statement.execute("ALTER TABLE chunk_ingest ADD COLUMN IF NOT EXISTS lane INTEGER");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS rollup_pending (
                          namespace VARCHAR,
                          pod VARCHAR,
                          container VARCHAR,
                          jvm_instance_id VARCHAR,
                          t_start BIGINT,
                          t_end BIGINT,
                          PRIMARY KEY (namespace, pod, container, jvm_instance_id, t_start, t_end)
                        )
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS chunk_ingest_status_t_start ON chunk_ingest(status, t_start)");
                statement.execute("UPDATE chunk_ingest SET status = 'done' WHERE status = 'ok'");
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
                return rows.next() && doneStatus(rows.getString(1));
            }
        }
    }

    public synchronized void record(String chunkId, String jvmInstanceId, String recordingId, long tStart, long tEnd,
            String status, long rows, String error) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                """
                UPDATE chunk_ingest
                SET jvm_instance_id = ?, recording_id = ?, t_start = ?, t_end = ?, status = ?, rows = ?, error = ?,
                    rows_written = ?
                WHERE chunk_id = ?
                """)) {
            update.setString(1, jvmInstanceId);
            update.setString(2, recordingId);
            update.setLong(3, tStart);
            update.setLong(4, tEnd);
            update.setString(5, "ok".equals(status) ? "done" : status);
            update.setLong(6, rows);
            update.setString(7, error);
            update.setLong(8, rows);
            update.setString(9, chunkId);
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                """
                INSERT INTO chunk_ingest
                  (chunk_id, jvm_instance_id, recording_id, t_start, t_end, source, status, rows, error, rows_written)
                VALUES (?, ?, ?, ?, ?, 'jfr', ?, ?, ?, ?)
                """)) {
            insert.setString(1, chunkId);
            insert.setString(2, jvmInstanceId);
            insert.setString(3, recordingId);
            insert.setLong(4, tStart);
            insert.setLong(5, tEnd);
            insert.setString(6, "ok".equals(status) ? "done" : status);
            insert.setLong(7, rows);
            insert.setString(8, error);
            insert.setLong(9, rows);
            insert.executeUpdate();
        }
    }

    /**
     * Instance already established for this recording. One JFR file is one JVM, so a later chunk belongs to that
     * instance even when it starts inside an earlier chunk's declared window. Prefer the non-provisional id so a
     * recording that already has both a real id and phantoms does not keep growing the phantom.
     */
    public synchronized String inheritInstance(String recordingId, long chunkStartNs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT ci.jvm_instance_id
                FROM chunk_ingest ci
                LEFT JOIN jvm_instance_state s ON s.jvm_instance_id = ci.jvm_instance_id
                WHERE ci.recording_id = ? AND ci.status IN ('ok', 'done')
                ORDER BY COALESCE(s.provisional, true) ASC,
                         CASE WHEN ci.t_start <= ? THEN 0 ELSE 1 END,
                         ci.t_start DESC, ci.t_end DESC
                LIMIT 1
                """)) {
            statement.setString(1, recordingId);
            statement.setLong(2, chunkStartNs);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    /**
     * Newest real instance for this pod UID. A missing JVMInformation chunk uses this instead of a time gap, so a
     * rotation stays on the JVM that already identified itself.
     */
    public synchronized String latestRealInstance(String namespace, String pod, String container, String podUid)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT jvm_instance_id FROM jvm_instance_state
                WHERE namespace = ? AND pod = ? AND container = ? AND provisional = false
                  AND (pod_uid IS NULL OR pod_uid = ?)
                ORDER BY CASE WHEN pod_uid = ? THEN 0 ELSE 1 END, instance_seq DESC, last_t_ns DESC
                LIMIT 1
                """)) {
            statement.setString(1, namespace);
            statement.setString(2, pod);
            statement.setString(3, container);
            statement.setString(4, podUid);
            statement.setString(5, podUid);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    public synchronized boolean isProvisional(String jvmInstanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT provisional FROM jvm_instance_state WHERE jvm_instance_id = ?")) {
            statement.setString(1, jvmInstanceId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    public synchronized boolean hasOtherRealInstance(String namespace, String pod, String container, String realId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT 1 FROM jvm_instance_state
                WHERE namespace = ? AND pod = ? AND container = ? AND provisional = false AND jvm_instance_id <> ?
                LIMIT 1
                """)) {
            statement.setString(1, namespace);
            statement.setString(2, pod);
            statement.setString(3, container);
            statement.setString(4, realId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /**
     * Chunks on this workload that start with the JVM just identified, and so must leave whatever id they were
     * written under. Earlier chunks of a previous JVM stay where they are.
     */
    public synchronized List<Claim> previewClaims(String namespace, String pod, String container, String realId, long startNs)
            throws SQLException {
        return previewClaims(namespace, pod, container, realId, startNs, false);
    }

    /**
     * @param claimAllProvisionals when this is the first real id for the pod, provisional chunks move onto it even if
     *     their header starts before the JVM start time
     */
    public synchronized List<Claim> previewClaims(
            String namespace, String pod, String container, String realId, long startNs, boolean claimAllProvisionals)
            throws SQLException {
        Map<String, ClaimBuilder> groups = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT ci.jvm_instance_id, ci.chunk_id, ci.t_start, ci.t_end, s.provisional
                FROM chunk_ingest ci
                JOIN jvm_instance_state s ON s.jvm_instance_id = ci.jvm_instance_id
                WHERE s.namespace = ? AND s.pod = ? AND s.container = ?
                  AND ci.status IN ('ok', 'done')
                  AND ci.jvm_instance_id <> ?
                  AND ((? = true AND s.provisional = true) OR ci.t_start >= ?)
                """)) {
            statement.setString(1, namespace);
            statement.setString(2, pod);
            statement.setString(3, container);
            statement.setString(4, realId);
            statement.setBoolean(5, claimAllProvisionals);
            statement.setLong(6, startNs - CLAIM_SLACK_NS);
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
        boolean previous = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (Claim claim : claims) {
                queueRollup(claim.fromId(), realId, claim.minStartNs(), claim.maxEndNs());
                if (!claim.emptied()) {
                    queueRollup(claim.fromId(), claim.fromId(), claim.minStartNs(), claim.maxEndNs());
                }
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
                            (SELECT max(t_end) FROM chunk_ingest WHERE jvm_instance_id = ? AND status IN ('ok', 'done')), 0)
                        WHERE jvm_instance_id = ?
                        """)) {
                    rewind.setString(1, claim.fromId());
                    rewind.setString(2, claim.fromId());
                    rewind.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(previous);
        }
    }

    public synchronized void queueRollup(String sourceInstanceId, String instanceId, long tStart, long tEnd) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                """
                INSERT INTO rollup_pending (namespace, pod, container, jvm_instance_id, t_start, t_end)
                SELECT namespace, pod, container, ?, ?, ? FROM jvm_instance_state WHERE jvm_instance_id = ?
                ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, instanceId);
            insert.setLong(2, tStart);
            insert.setLong(3, tEnd);
            insert.setString(4, sourceInstanceId);
            insert.executeUpdate();
        }
    }

    public synchronized List<RollupJob> pendingRollups() throws SQLException {
        List<RollupJob> jobs = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT namespace, pod, container, jvm_instance_id, t_start, t_end FROM rollup_pending")) {
            while (rows.next()) {
                jobs.add(new RollupJob(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getLong(5),
                        rows.getLong(6)));
            }
        }
        return jobs;
    }

    public synchronized void deleteRollups(List<RollupJob> jobs) throws SQLException {
        if (jobs == null || jobs.isEmpty()) {
            return;
        }
        boolean previous = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (RollupJob job : jobs) {
                deleteRollup(job);
            }
            connection.commit();
        } catch (SQLException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(previous);
        }
    }

    public synchronized void deleteRollup(RollupJob job) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                """
                DELETE FROM rollup_pending
                WHERE namespace IS NOT DISTINCT FROM ? AND pod IS NOT DISTINCT FROM ? AND container IS NOT DISTINCT FROM ?
                  AND jvm_instance_id = ? AND t_start = ? AND t_end = ?
                """)) {
            delete.setString(1, job.namespace());
            delete.setString(2, job.pod());
            delete.setString(3, job.container());
            delete.setString(4, job.instanceId());
            delete.setLong(5, job.tStart());
            delete.setLong(6, job.tEnd());
            delete.executeUpdate();
        }
    }

    private int countForInstance(String jvmInstanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM chunk_ingest WHERE jvm_instance_id = ? AND status IN ('ok', 'done')")) {
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
        return ensureInstance(jvmInstanceId, namespace, pod, container, recordingId, jvmStartMs, provisional, null);
    }

    public synchronized InstanceState ensureInstance(
            String jvmInstanceId,
            String namespace,
            String pod,
            String container,
            String recordingId,
            long jvmStartMs,
            boolean provisional,
            String podUid) throws SQLException {
        try (PreparedStatement existing = connection.prepareStatement(
                "SELECT instance_seq, restart_reason_hint FROM jvm_instance_state WHERE jvm_instance_id = ?")) {
            existing.setString(1, jvmInstanceId);
            try (ResultSet rows = existing.executeQuery()) {
                if (rows.next()) {
                    if (podUid != null) {
                        try (PreparedStatement uid = connection.prepareStatement(
                                "UPDATE jvm_instance_state SET pod_uid = ? WHERE jvm_instance_id = ? AND pod_uid IS NULL")) {
                            uid.setString(1, podUid);
                            uid.setString(2, jvmInstanceId);
                            uid.executeUpdate();
                        }
                    }
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
                   provisional, restart_reason_hint, last_t_ns, pod_uid)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            insert.setString(11, podUid);
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
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM chunk_ingest WHERE status IN ('ok', 'done')")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    public synchronized void setLanes(int lanes) throws SQLException {
        this.lanes = Math.max(1, lanes);
        try (PreparedStatement clear = connection.prepareStatement("UPDATE chunk_ingest SET lane = NULL WHERE lane >= ?")) {
            clear.setInt(1, this.lanes);
            clear.executeUpdate();
        }
        assignLanes();
    }

    public synchronized void enqueueAll(java.util.Collection<ChunkMetadata> chunks) throws SQLException {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        boolean previous = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (ChunkMetadata metadata : chunks) {
                enqueue(metadata);
            }
            connection.commit();
        } catch (SQLException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(previous);
        }
    }

    public synchronized int backlog() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM chunk_ingest WHERE status IN ('pending', 'decoding', 'failed', 'error')")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    public synchronized boolean hasReadyWork(int lane, Instant now) throws SQLException {
        long nowNs = now.toEpochMilli() * 1_000_000L;
        try (PreparedStatement statement = connection.prepareStatement(
                """
                WITH ranked AS (
                  SELECT status, attempts, next_attempt_ns, lane,
                         ROW_NUMBER() OVER (PARTITION BY namespace, pod, container ORDER BY t_start, chunk_id) AS rn
                  FROM chunk_ingest
                  WHERE status IN ('pending', 'decoding', 'failed', 'error')
                )
                SELECT 1 FROM ranked
                WHERE rn = 1
                  AND status IN ('pending', 'failed', 'error')
                  AND (status <> 'failed' OR (coalesce(attempts, 0) < ? AND (next_attempt_ns IS NULL OR next_attempt_ns <= ?)))
                  AND lane = ?
                LIMIT 1
                """)) {
            statement.setInt(1, MAX_ATTEMPTS);
            statement.setLong(2, nowNs);
            statement.setInt(3, lane);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    public synchronized int requeue(String chunkId) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                """
                UPDATE chunk_ingest
                SET status = 'pending', attempts = 0, error = NULL, next_attempt_ns = NULL
                WHERE status = 'quarantined' AND chunk_id = ?
                """)) {
            update.setString(1, chunkId);
            return update.executeUpdate();
        }
    }

    public synchronized int requeueAll() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(
                    """
                    UPDATE chunk_ingest
                    SET status = 'pending', attempts = 0, error = NULL, next_attempt_ns = NULL
                    WHERE status = 'quarantined'
                    """);
        }
    }

    public synchronized void enqueue(ChunkMetadata metadata) throws SQLException {
        if (metadata == null) {
            return;
        }
        String status = statusOf(metadata.chunkId());
        if ("error".equals(status)) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE chunk_ingest SET status = 'pending', attempts = 0, next_attempt_ns = NULL, lane = ? WHERE chunk_id = ?")) {
                update.setInt(1, DecodeLanes.of(metadata.namespace(), metadata.podName(), metadata.containerName(), lanes));
                update.setString(2, metadata.chunkId());
                update.executeUpdate();
            }
            return;
        }
        if (doneStatus(status) || "decoding".equals(status) || "quarantined".equals(status) || "pending".equals(status)
                || "failed".equals(status)) {
            return;
        }
        long tEnd = metadata.chunkStartTimeNs() + Math.max(0L, metadata.chunkDurationNs());
        try (PreparedStatement insert = connection.prepareStatement(
                """
                INSERT INTO chunk_ingest
                  (chunk_id, recording_id, t_start, t_end, source, status, rows, attempts, namespace, pod, container, pod_uid, lane)
                VALUES (?, ?, ?, ?, 'jfr', 'pending', 0, 0, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, metadata.chunkId());
            insert.setString(2, metadata.recordingId());
            insert.setLong(3, metadata.chunkStartTimeNs());
            insert.setLong(4, tEnd);
            insert.setString(5, metadata.namespace());
            insert.setString(6, metadata.podName());
            insert.setString(7, metadata.containerName());
            insert.setString(8, metadata.podUid());
            insert.setInt(9, DecodeLanes.of(metadata.namespace(), metadata.podName(), metadata.containerName(), lanes));
            insert.executeUpdate();
        }
    }

    public synchronized void assignLanes() throws SQLException {
        List<String[]> pods = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT DISTINCT namespace, pod, container FROM chunk_ingest WHERE lane IS NULL")) {
            while (rows.next()) {
                pods.add(new String[] {rows.getString(1), rows.getString(2), rows.getString(3)});
            }
        }
        try (PreparedStatement update = connection.prepareStatement(
                """
                UPDATE chunk_ingest SET lane = ?
                WHERE namespace IS NOT DISTINCT FROM ? AND pod IS NOT DISTINCT FROM ? AND container IS NOT DISTINCT FROM ?
                  AND lane IS NULL
                """)) {
            for (String[] pod : pods) {
                update.setInt(1, DecodeLanes.of(pod[0], pod[1], pod[2], lanes));
                update.setString(2, pod[0]);
                update.setString(3, pod[1]);
                update.setString(4, pod[2]);
                update.executeUpdate();
            }
        }
    }

    public synchronized int resetDecoding() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate("UPDATE chunk_ingest SET status = 'pending' WHERE status = 'decoding'");
        }
    }

    public synchronized Claimed claim(int lane, int lanes, Instant now) throws SQLException {
        return claim(lane, lanes, now, false);
    }

    public synchronized Claimed claimAny(Instant now) throws SQLException {
        return claim(0, 1, now, true);
    }

    public synchronized void complete(String chunkId, String jvmInstanceId, String recordingId, long tStart, long tEnd, long rows,
            long eventsSeen, String hint) throws SQLException {
        boolean previous = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            record(chunkId, jvmInstanceId, recordingId, tStart, tEnd, "done", rows, null);
            try (PreparedStatement events = connection.prepareStatement(
                    "UPDATE chunk_ingest SET events_seen = ?, rows_written = ? WHERE chunk_id = ?")) {
                events.setLong(1, eventsSeen);
                events.setLong(2, rows);
                events.setString(3, chunkId);
                events.executeUpdate();
            }
            touchInstance(jvmInstanceId, tEnd, hint);
            queueRollup(jvmInstanceId, jvmInstanceId, tStart, tEnd);
            connection.commit();
        } catch (SQLException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(previous);
        }
    }

    public synchronized void fail(String chunkId, String reason) throws SQLException {
        int attempts = attemptsOf(chunkId) + 1;
        String status = attempts >= MAX_ATTEMPTS ? "quarantined" : "failed";
        long next = Instant.now().toEpochMilli() * 1_000_000L + backoffNs(attempts);
        try (PreparedStatement update = connection.prepareStatement(
                """
                UPDATE chunk_ingest
                SET status = ?, attempts = ?, error = ?, next_attempt_ns = ?, jvm_instance_id = NULL
                WHERE chunk_id = ?
                """)) {
            update.setString(1, status);
            update.setInt(2, attempts);
            update.setString(3, reason);
            update.setLong(4, next);
            update.setString(5, chunkId);
            if (update.executeUpdate() == 0) {
                try (PreparedStatement insert = connection.prepareStatement(
                        """
                        INSERT INTO chunk_ingest
                          (chunk_id, t_start, t_end, source, status, rows, attempts, error, next_attempt_ns)
                        VALUES (?, 0, 0, 'jfr', ?, 0, ?, ?, ?)
                        """)) {
                    insert.setString(1, chunkId);
                    insert.setString(2, status);
                    insert.setInt(3, attempts);
                    insert.setString(4, reason);
                    insert.setLong(5, next);
                    insert.executeUpdate();
                }
            }
        }
        try {
            InstanceFiles.deleteChunk(v2Root, chunkId);
        } catch (java.io.IOException error) {
            throw new SQLException("could not delete partial files for " + chunkId, error);
        }
    }

    public synchronized void quarantine(String chunkId, String reason) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE chunk_ingest SET status = 'quarantined', error = ? WHERE chunk_id = ?")) {
            update.setString(1, reason);
            update.setString(2, chunkId);
            if (update.executeUpdate() == 0) {
                record(chunkId, null, null, 0L, 0L, "quarantined", 0L, reason);
            }
        }
    }

    public synchronized boolean isDecodingPod(String namespace, String pod, String container) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM chunk_ingest WHERE namespace = ? AND pod = ? AND container = ? AND status = 'decoding' LIMIT 1")) {
            statement.setString(1, namespace);
            statement.setString(2, pod);
            statement.setString(3, container);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    public synchronized int quarantineCount() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM chunk_ingest WHERE status = 'quarantined'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    public synchronized List<QuarantineRow> quarantined() throws SQLException {
        List<QuarantineRow> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        """
                        SELECT chunk_id, namespace, pod, container, attempts, error
                        FROM chunk_ingest WHERE status = 'quarantined' ORDER BY chunk_id
                        """)) {
            while (result.next()) {
                rows.add(new QuarantineRow(result.getString(1), result.getString(2), result.getString(3), result.getString(4),
                        result.getInt(5), result.getString(6)));
            }
        }
        return rows;
    }

    public synchronized List<CoverageRow> coverage() throws SQLException {
        List<CoverageRow> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        """
                        SELECT namespace, pod, container, status, count(*),
                               min(t_start), max(t_end), coalesce(sum(events_seen), 0), coalesce(sum(rows_written), 0)
                        FROM chunk_ingest
                        GROUP BY 1, 2, 3, 4
                        """)) {
            while (result.next()) {
                rows.add(new CoverageRow(result.getString(1), result.getString(2), result.getString(3), result.getString(4),
                        result.getLong(5), result.getLong(6), result.getLong(7), result.getLong(8), result.getLong(9)));
            }
        }
        return rows;
    }

    private Claimed claim(int lane, int lanes, Instant now, boolean anyLane) throws SQLException {
        long nowNs = now.toEpochMilli() * 1_000_000L;
        Claimed chosen;
        try (PreparedStatement statement = connection.prepareStatement(
                """
                WITH ranked AS (
                  SELECT chunk_id, namespace, pod, container, pod_uid, recording_id, t_start, t_end, status, attempts, next_attempt_ns, lane,
                         ROW_NUMBER() OVER (PARTITION BY namespace, pod, container ORDER BY t_start, chunk_id) AS rn
                  FROM chunk_ingest
                  WHERE status IN ('pending', 'decoding', 'failed', 'error')
                )
                SELECT chunk_id, namespace, pod, container, pod_uid, recording_id, t_start, t_end
                FROM ranked
                WHERE rn = 1
                  AND status IN ('pending', 'failed', 'error')
                  AND (status <> 'failed' OR (coalesce(attempts, 0) < ? AND (next_attempt_ns IS NULL OR next_attempt_ns <= ?)))
                  AND (? OR lane = ?)
                ORDER BY t_start, chunk_id
                LIMIT 1
                """)) {
            statement.setInt(1, MAX_ATTEMPTS);
            statement.setLong(2, nowNs);
            statement.setBoolean(3, anyLane);
            statement.setInt(4, lane);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                chosen = new Claimed(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5),
                        rows.getString(6), rows.getLong(7), rows.getLong(8));
            }
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE chunk_ingest SET status = 'decoding', error = NULL WHERE chunk_id = ? AND status IN ('pending', 'failed', 'error')")) {
            update.setString(1, chosen.chunkId());
            if (update.executeUpdate() == 0) {
                return null;
            }
        }
        return chosen;
    }

    public synchronized void fillIdentity(String chunkId, ChunkMetadata metadata) throws SQLException {
        if (metadata == null) {
            return;
        }
        try (PreparedStatement update = connection.prepareStatement(
                """
                UPDATE chunk_ingest
                SET namespace = ?, pod = ?, container = ?, pod_uid = ?, recording_id = coalesce(recording_id, ?)
                WHERE chunk_id = ? AND (namespace IS NULL OR pod IS NULL OR container IS NULL)
                """)) {
            update.setString(1, metadata.namespace());
            update.setString(2, metadata.podName());
            update.setString(3, metadata.containerName());
            update.setString(4, metadata.podUid());
            update.setString(5, metadata.recordingId());
            update.setString(6, chunkId);
            update.executeUpdate();
        }
    }

    public synchronized List<String> chunksMissingIdentity() throws SQLException {
        List<String> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT chunk_id FROM chunk_ingest WHERE namespace IS NULL OR pod IS NULL OR container IS NULL")) {
            while (rows.next()) {
                ids.add(rows.getString(1));
            }
        }
        return ids;
    }

    public synchronized void reopen(String chunkId) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                """
                UPDATE chunk_ingest
                SET status = 'pending', attempts = 0, next_attempt_ns = NULL, error = NULL
                WHERE chunk_id = ? AND status IN ('ok', 'done')
                """)) {
            update.setString(1, chunkId);
            update.executeUpdate();
        }
    }

    private String statusOf(String chunkId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT status FROM chunk_ingest WHERE chunk_id = ?")) {
            statement.setString(1, chunkId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private int attemptsOf(String chunkId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT coalesce(attempts, 0) FROM chunk_ingest WHERE chunk_id = ?")) {
            statement.setString(1, chunkId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private static boolean doneStatus(String status) {
        return "ok".equals(status) || "done".equals(status);
    }

    static long backoffNs(int attempts) {
        int shift = Math.max(0, Math.min(attempts - 1, 4));
        return (1L << shift) * 1_000_000_000L;
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

    public record Claimed(
            String chunkId, String namespace, String pod, String container, String podUid, String recordingId, long tStart, long tEnd) {}

    public record QuarantineRow(String chunkId, String namespace, String pod, String container, int attempts, String error) {}

    public record CoverageRow(
            String namespace, String pod, String container, String status, long chunks, long fromNs, long toNs, long eventsSeen,
            long rowsWritten) {}

    public record RollupJob(String namespace, String pod, String container, String instanceId, long tStart, long tEnd) {}

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

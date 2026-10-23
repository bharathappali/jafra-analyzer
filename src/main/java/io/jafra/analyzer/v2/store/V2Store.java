package io.jafra.analyzer.v2.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.jafra.analyzer.v2.rollup.SettingsMerge;
import io.jafra.analyzer.v2.rollup.WindowData;

/**
 * Separate DuckDB file for v2 rollups. One synchronized write connection. Reads use
 * {@link DuckDBConnection#duplicate()}. An unreadable file is renamed and recreated so a rescan can
 * rebuild it from stored chunks.
 */
@ApplicationScoped
public class V2Store {
    public static final String DATABASE_FILE = "v2.duckdb";
    private static final Logger LOG = Logger.getLogger(V2Store.class);

    private final Path databaseFile;
    private Connection connection;

    @Inject
    public V2Store(
            @ConfigProperty(name = "jafra.storage.root", defaultValue = "/var/lib/jafra/analyzer") String root) {
        this(Path.of(root));
    }

    public V2Store(Path storageRoot) {
        this.databaseFile = storageRoot.resolve("data").resolve(DATABASE_FILE);
        try {
            Files.createDirectories(databaseFile.getParent());
            Class.forName("org.duckdb.DuckDBDriver");
            try {
                this.connection = connect();
                createSchema();
            } catch (SQLException openError) {
                closeQuietly();
                renameBroken();
                this.connection = connect();
                createSchema();
            }
        } catch (IOException | SQLException | ClassNotFoundException error) {
            throw new IllegalStateException("unable to open v2 store " + databaseFile, error);
        }
    }

    public Path databaseFile() {
        return databaseFile;
    }

    /**
     * Writes one window. Counter resets (a cumulative value that drops) skip that metric's rate.
     * Reprocessing the same chunk is a no-op. Returns false when nothing was written.
     */
    public synchronized CommitResult commit(String chunkId, WindowData data) throws IOException {
        if (chunkId == null || chunkId.isBlank() || data == null) {
            return CommitResult.SKIPPED;
        }
        boolean previous = true;
        try {
            previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            if (chunkProcessed(connection, chunkId)) {
                connection.rollback();
                return CommitResult.SKIPPED;
            }
            int jvmId = upsertJvm(data);
            boolean late = isLate(connection, jvmId, data.fromMs);
            if (late) {
                LOG.infof("late window %s at %d is rolled up without re-running detectors", chunkId, data.fromMs);
            }
            if (windowExists(connection, jvmId, data.fromMs)) {
                LOG.warnf("window %d already exists for jvm %d, skipping chunk %s", data.fromMs, jvmId, chunkId);
                connection.rollback();
                return CommitResult.SKIPPED;
            }
            applyCounters(connection, jvmId, data, late);
            if (data.derive != null) {
                data.derive.run();
            }
            data.compact();
            deletePartial(connection, jvmId, data.fromMs, data.toMs);
            appendRollup(jvmId, data);
            appendHist(jvmId, data);
            appendEvents(jvmId, data);
            appendInternalEvents(jvmId, data);
            appendInternalSeries(jvmId, data);
            insertStacks(data);
            appendSamples(jvmId, data);
            insertWindow(connection, jvmId, chunkId, data, late);
            rebuildMinute(jvmId, data.fromMs);
            touchJvm(connection, jvmId, data);
            connection.commit();
            return new CommitResult(true, jvmId, late);
        } catch (SQLException error) {
            rollbackQuietly();
            throw io(error);
        } finally {
            restoreAutoCommit(previous);
        }
    }

    public synchronized boolean chunkProcessed(String chunkId) throws IOException {
        try {
            return chunkProcessed(connection, chunkId);
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public List<RollupRow> rollup5s() throws IOException {
        return rollup("rollup_5s");
    }

    public List<RollupRow> rollup1m() throws IOException {
        return rollup("rollup_1m");
    }

    public List<HistRow> histRows() throws IOException {
        String sql = """
                SELECT jvm_id, metric, key, ts, bucket, count
                FROM hist
                ORDER BY jvm_id, metric, key, ts, bucket
                """;
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            List<HistRow> found = new ArrayList<>();
            while (rows.next()) {
                found.add(new HistRow(
                        rows.getInt(1),
                        rows.getString(2),
                        rows.getString(3),
                        rows.getLong(4),
                        rows.getInt(5),
                        rows.getLong(6)));
            }
            return found;
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public List<JvmRow> findJvms(String namespace, String pod, String container, String cluster) throws IOException {
        StringBuilder sql = new StringBuilder("""
                SELECT jvm_id, cluster_id, namespace, pod_name, pod_uid, container_name, jvm_start_ms,
                       jdk_major, jdk_version, profile, first_ms, last_ms, windows, settings
                FROM jvms WHERE namespace = ? AND pod_name = ? AND container_name = ?
                """);
        if (cluster != null && !cluster.isBlank()) {
            sql.append(" AND cluster_id = ?");
        }
        sql.append(" ORDER BY jvm_start_ms");
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql.toString())) {
            statement.setString(1, namespace);
            statement.setString(2, pod);
            statement.setString(3, container);
            if (cluster != null && !cluster.isBlank()) {
                statement.setString(4, cluster);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return readJvms(rows);
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public List<JvmRow> allJvms() throws IOException {
        String sql = """
                SELECT jvm_id, cluster_id, namespace, pod_name, pod_uid, container_name, jvm_start_ms,
                       jdk_major, jdk_version, profile, first_ms, last_ms, windows, settings
                FROM jvms ORDER BY namespace, pod_name, container_name, jvm_start_ms
                """;
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            return readJvms(rows);
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public JvmRow jvm(int jvmId) throws IOException {
        String sql = """
                SELECT jvm_id, cluster_id, namespace, pod_name, pod_uid, container_name, jvm_start_ms,
                       jdk_major, jdk_version, profile, first_ms, last_ms, windows, settings
                FROM jvms WHERE jvm_id = ?
                """;
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setInt(1, jvmId);
            try (ResultSet rows = statement.executeQuery()) {
                List<JvmRow> found = readJvms(rows);
                return found.isEmpty() ? null : found.get(0);
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public List<RollupRow> readRollup(
            boolean minute, int jvmId, String metric, long fromMs, long toMs, String byName, String key)
            throws IOException {
        String table = minute ? "rollup_1m" : "rollup_5s";
        StringBuilder sql = new StringBuilder(
                "SELECT jvm_id, metric, by_name, key, ts, min, max, avg, count, sum FROM " + table
                        + " WHERE jvm_id = ? AND metric = ? AND ts >= ? AND ts < ?");
        if (byName != null) {
            sql.append(" AND by_name = ?");
        }
        if (key != null) {
            sql.append(" AND key = ?");
        }
        sql.append(" ORDER BY ts, key");
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setInt(index++, jvmId);
            statement.setString(index++, metric);
            statement.setLong(index++, fromMs);
            statement.setLong(index++, toMs);
            if (byName != null) {
                statement.setString(index++, byName);
            }
            if (key != null) {
                statement.setString(index++, key);
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<RollupRow> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(mapRollup(rows));
                }
                return found;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public Map<Integer, Long> readHist(int jvmId, String metric, String key, long fromMs, long toMs) throws IOException {
        String sql = """
                SELECT bucket, SUM(count) FROM hist
                WHERE jvm_id = ? AND metric = ? AND key = ? AND ts >= ? AND ts < ?
                GROUP BY bucket
                """;
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setInt(1, jvmId);
            statement.setString(2, metric);
            statement.setString(3, key == null ? "" : key);
            statement.setLong(4, fromMs);
            statement.setLong(5, toMs);
            try (ResultSet rows = statement.executeQuery()) {
                Map<Integer, Long> counts = new java.util.TreeMap<>();
                while (rows.next()) {
                    long count = rows.getLong(2);
                    if (count > 0) {
                        counts.put(rows.getInt(1), count);
                    }
                }
                return counts;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public Long nearestTs(int jvmId, String metric, long aroundMs) throws IOException {
        String sql = """
                SELECT ts FROM rollup_5s
                WHERE jvm_id = ? AND metric = ? AND by_name = '' AND key = ''
                ORDER BY abs(ts - ?) LIMIT 1
                """;
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setInt(1, jvmId);
            statement.setString(2, metric);
            statement.setLong(3, aroundMs);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return rows.getLong(1);
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    /** Nearest 5 s rollup window as {@code [fromMs, toMs]}, or null when the metric has no rows. */
    public long[] nearestWindow(int jvmId, String metric, long aroundMs) throws IOException {
        Long ts = nearestTs(jvmId, metric, aroundMs);
        if (ts == null) {
            return null;
        }
        return new long[] {ts, ts + 5_000L};
    }

    /** Last unflagged 5s totals with {@code ts < beforeMs}, newest first. */
    public List<RollupRow> recentTotals(int jvmId, String metric, String group, long beforeMs, int limit)
            throws IOException {
        String sql = """
                SELECT r.jvm_id, r.metric, r.by_name, r.key, r.ts, r.min, r.max, r.avg, r.count, r.sum
                FROM rollup_5s r
                WHERE r.jvm_id = ? AND r.metric = ? AND r.by_name = '' AND r.key = '' AND r.ts < ?
                  AND NOT EXISTS (
                    SELECT 1 FROM incident_windows w
                    WHERE w.jvm_id = r.jvm_id AND w.group_name = ? AND w.ts = r.ts)
                ORDER BY r.ts DESC
                LIMIT ?
                """;
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setInt(1, jvmId);
            statement.setString(2, metric);
            statement.setLong(3, beforeMs);
            statement.setString(4, group);
            statement.setInt(5, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<RollupRow> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(mapRollup(rows));
                }
                return found;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    /**
     * Last {@code limit} windows before {@code beforeMs}, skipping same-group incident windows.
     * A window with no rollup row is still returned ({@link BaselineSlot#present()} false).
     */
    public List<BaselineSlot> baselineSlots(
            int jvmId, String metric, String key, String group, long beforeMs, int limit) throws IOException {
        boolean internal = metric != null && metric.startsWith("internal.");
        String valueColumns = internal ? "s.value, s.value, s.value, 1, s.value" : "r.min, r.max, r.avg, r.count, r.sum";
        String join = internal
                ? "LEFT JOIN internal_series s ON s.jvm_id = ? AND s.ts = c.ts AND s.name = ? AND s.key = ?"
                : "LEFT JOIN rollup_5s r ON r.jvm_id = ? AND r.ts = c.ts AND r.metric = ? AND r.by_name = '' AND r.key = ?";
        String sql = """
                WITH chosen AS (
                    SELECT w.ts
                    FROM windows w
                    WHERE w.jvm_id = ?
                      AND w.ts < ?
                      AND NOT EXISTS (
                        SELECT 1 FROM incident_windows i
                        WHERE i.jvm_id = w.jvm_id AND i.group_name = ? AND i.ts = w.ts)
                    ORDER BY w.ts DESC
                    LIMIT ?)
                SELECT c.ts, %s
                FROM chosen c
                %s
                ORDER BY c.ts
                """.formatted(valueColumns, join);
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setInt(1, jvmId);
            statement.setLong(2, beforeMs);
            statement.setString(3, group == null ? "" : group);
            statement.setInt(4, limit);
            statement.setInt(5, jvmId);
            statement.setString(6, metric);
            statement.setString(7, key == null ? "" : key);
            try (ResultSet rows = statement.executeQuery()) {
                List<BaselineSlot> found = new ArrayList<>();
                while (rows.next()) {
                    long ts = rows.getLong(1);
                    double min = rows.getDouble(2);
                    boolean present = !rows.wasNull();
                    if (!present) {
                        found.add(new BaselineSlot(ts, false, 0, 0, 0, 0, 0));
                        continue;
                    }
                    double max = rows.getDouble(3);
                    double avg = rows.getDouble(4);
                    long count = rows.getLong(5);
                    double sum = rows.getDouble(6);
                    found.add(new BaselineSlot(ts, true, min, max, avg, count, sum));
                }
                return found;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public Map<Long, Map<Integer, Long>> readHistAt(int jvmId, String metric, String key, List<Long> timestamps)
            throws IOException {
        Map<Long, Map<Integer, Long>> found = new LinkedHashMap<>();
        if (timestamps == null || timestamps.isEmpty()) {
            return found;
        }
        String sql = "SELECT ts, bucket, count FROM hist WHERE jvm_id = ? AND metric = ? AND key = ? AND ts IN ("
                + String.join(", ", java.util.Collections.nCopies(timestamps.size(), "?")) + ")";
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            int index = 1;
            statement.setInt(index++, jvmId);
            statement.setString(index++, metric);
            statement.setString(index++, key == null ? "" : key);
            for (long ts : timestamps) {
                statement.setLong(index++, ts);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long count = rows.getLong(3);
                    if (count <= 0) {
                        continue;
                    }
                    found.computeIfAbsent(rows.getLong(1), ignored -> new java.util.TreeMap<>())
                            .merge(rows.getInt(2), count, Long::sum);
                }
                return found;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public List<RollupRow> readBreakdown(int jvmId, String metric, String byName, long ts) throws IOException {
        String sql = """
                SELECT jvm_id, metric, by_name, key, ts, min, max, avg, count, sum
                FROM rollup_5s
                WHERE jvm_id = ? AND ts = ? AND metric = ? AND by_name = ?
                ORDER BY sum DESC
                """;
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setInt(1, jvmId);
            statement.setLong(2, ts);
            statement.setString(3, metric);
            statement.setString(4, byName == null ? "" : byName);
            try (ResultSet rows = statement.executeQuery()) {
                List<RollupRow> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(mapRollup(rows));
                }
                return found;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public RollupRow lastTotalBefore(int jvmId, String metric, long beforeMs) throws IOException {
        String sql = """
                SELECT jvm_id, metric, by_name, key, ts, min, max, avg, count, sum
                FROM rollup_5s
                WHERE jvm_id = ? AND metric = ? AND by_name = '' AND key = '' AND ts < ?
                ORDER BY ts DESC
                LIMIT 1
                """;
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setInt(1, jvmId);
            statement.setString(2, metric);
            statement.setLong(3, beforeMs);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return mapRollup(rows);
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    /** Newest first, including {@code throughMs}. */
    public List<Long> recentWindowStarts(int jvmId, long throughMs, int limit) throws IOException {
        String sql = "SELECT ts FROM windows WHERE jvm_id = ? AND ts <= ? ORDER BY ts DESC LIMIT ?";
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setInt(1, jvmId);
            statement.setLong(2, throughMs);
            statement.setInt(3, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<Long> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(rows.getLong(1));
                }
                return found;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public List<EventRow> readInternalEvents(int jvmId, String name, long fromMs, long toMs) throws IOException {
        String sql = """
                SELECT name, ts, duration_us, thread, fields
                FROM internal_events
                WHERE jvm_id = ? AND name = ? AND ts >= ? AND ts < ?
                ORDER BY ts
                """;
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setInt(1, jvmId);
            statement.setString(2, name);
            statement.setLong(3, fromMs);
            statement.setLong(4, toMs);
            try (ResultSet rows = statement.executeQuery()) {
                List<EventRow> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new EventRow(
                            rows.getString(1),
                            rows.getLong(2),
                            nullableLong(rows, 3),
                            rows.getString(4),
                            null,
                            rows.getString(5)));
                }
                return found;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public List<IncidentRecord> openIncidents() throws IOException {
        String sql = """
                SELECT id, jvm_id, type, group_name, level, status, t0, t1, peak_at, score, confidence,
                       headline, felt, when_json, how_bad, together, who, where_json, todo, sources, flags
                FROM incidents WHERE status = 'open'
                """;
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            List<IncidentRecord> found = new ArrayList<>();
            while (rows.next()) {
                found.add(readIncident(rows));
            }
            return found;
        } catch (SQLException error) {
            throw io(error);
        }
    }

    /** Latest incident_windows.ts for this incident, or -1 when none exist. */
    public long lastIncidentWindowTs(String id) throws IOException {
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(
                        "SELECT MAX(ts) FROM incident_windows WHERE incident_id = ?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return -1L;
                }
                long ts = rows.getLong(1);
                return rows.wasNull() ? -1L : ts;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public Long windowEnd(int jvmId, long ts) throws IOException {
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(
                        "SELECT end_ts FROM windows WHERE jvm_id = ? AND ts = ?")) {
            statement.setInt(1, jvmId);
            statement.setLong(2, ts);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return rows.getLong(1);
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public int countWindowsAfter(int jvmId, long ts) throws IOException {
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(
                        "SELECT COUNT(*) FROM windows WHERE jvm_id = ? AND ts > ?")) {
            statement.setInt(1, jvmId);
            statement.setLong(2, ts);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public Map<Long, Map<Integer, Long>> readHistByWindow(int jvmId, String metric, long fromMs, long toMs)
            throws IOException {
        String sql = """
                SELECT ts, bucket, count FROM hist
                WHERE jvm_id = ? AND metric = ? AND key = '' AND ts >= ? AND ts < ?
                ORDER BY ts, bucket
                """;
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setInt(1, jvmId);
            statement.setString(2, metric);
            statement.setLong(3, fromMs);
            statement.setLong(4, toMs);
            try (ResultSet rows = statement.executeQuery()) {
                Map<Long, Map<Integer, Long>> found = new java.util.LinkedHashMap<>();
                while (rows.next()) {
                    long count = rows.getLong(3);
                    if (count <= 0) {
                        continue;
                    }
                    found.computeIfAbsent(rows.getLong(1), ignored -> new java.util.TreeMap<>())
                            .merge(rows.getInt(2), count, Long::sum);
                }
                return found;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    /** Worst overlapping incident level per group. critical beats warning. */
    public Map<String, String> incidentLevels(int jvmId, long fromMs, long toMs) throws IOException {
        String sql = """
                SELECT group_name, level FROM incidents
                WHERE jvm_id = ? AND t0 < ? AND (t1 IS NULL OR t1 >= ?)
                """;
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setInt(1, jvmId);
            statement.setLong(2, toMs);
            statement.setLong(3, fromMs);
            try (ResultSet rows = statement.executeQuery()) {
                Map<String, String> levels = new java.util.LinkedHashMap<>();
                while (rows.next()) {
                    String group = rows.getString(1);
                    String level = rows.getString(2);
                    String current = levels.get(group);
                    if (current == null || levelRank(level) > levelRank(current)) {
                        levels.put(group, level);
                    }
                }
                return levels;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public List<EventRow> readEvents(
            int jvmId, java.util.Collection<String> types, long fromMs, long toMs, boolean fromExclusive, int limit)
            throws IOException {
        StringBuilder sql = new StringBuilder(
                "SELECT type, ts, duration_us, thread, top_frame, fields FROM events WHERE jvm_id = ? AND ts ");
        sql.append(fromExclusive ? "> ? " : ">= ? ");
        sql.append("AND ts < ?");
        if (types != null && !types.isEmpty()) {
            sql.append(" AND type IN (");
            sql.append(String.join(", ", java.util.Collections.nCopies(types.size(), "?")));
            sql.append(")");
        }
        sql.append(" ORDER BY duration_us DESC NULLS LAST LIMIT ?");
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setInt(index++, jvmId);
            statement.setLong(index++, fromMs);
            statement.setLong(index++, toMs);
            if (types != null) {
                for (String type : types) {
                    statement.setString(index++, type);
                }
            }
            statement.setInt(index, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<EventRow> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(readEvent(rows));
                }
                return found;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public List<SampleRow> readSamples(
            int jvmId, String kind, long fromMs, long toMs, String thread, String state, String handler)
            throws IOException {
        StringBuilder sql = new StringBuilder(
                "SELECT ts, kind, stack_id, thread, state, handler, count, weight, lock_class FROM samples WHERE jvm_id = ? AND ts >= ? AND ts < ?");
        if (kind != null) {
            sql.append(" AND kind = ?");
        }
        if (thread != null) {
            sql.append(" AND thread = ?");
        }
        if (state != null) {
            sql.append(" AND state = ?");
        }
        if (handler != null) {
            sql.append(" AND handler = ?");
        }
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setInt(index++, jvmId);
            statement.setLong(index++, fromMs);
            statement.setLong(index++, toMs);
            if (kind != null) {
                statement.setString(index++, kind);
            }
            if (thread != null) {
                statement.setString(index++, thread);
            }
            if (state != null) {
                statement.setString(index++, state);
            }
            if (handler != null) {
                statement.setString(index++, handler);
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<SampleRow> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new SampleRow(
                            rows.getLong(1),
                            rows.getString(2),
                            rows.getLong(3),
                            rows.getString(4),
                            rows.getString(5),
                            rows.getString(6),
                            rows.getLong(7),
                            rows.getDouble(8),
                            rows.getString(9)));
                }
                return found;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public Map<Long, String> readStackJson(java.util.Collection<Long> ids) throws IOException {
        Map<Long, String> found = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return found;
        }
        List<Long> list = new ArrayList<>(ids);
        String sql = "SELECT stack_id, frames FROM stacks WHERE stack_id IN ("
                + String.join(", ", java.util.Collections.nCopies(list.size(), "?")) + ")";
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            for (int i = 0; i < list.size(); i++) {
                statement.setLong(i + 1, list.get(i));
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.put(rows.getLong(1), rows.getString(2));
                }
                return found;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public List<EventRow> queryEvents(
            int jvmId,
            String type,
            long fromMs,
            long toMs,
            String sort,
            String field,
            String expected,
            int limit)
            throws IOException {
        StringBuilder sql = new StringBuilder(
                "SELECT type, ts, duration_us, thread, top_frame, fields FROM events WHERE jvm_id = ? AND type = ? AND ts >= ? AND ts < ?");
        boolean fieldFilter = field != null && expected != null;
        if (fieldFilter) {
            if ("thread".equals(field)) {
                sql.append(" AND lower(thread) = lower(?)");
            } else if ("top_frame".equals(field)) {
                sql.append(" AND lower(top_frame) = lower(?)");
            } else {
                sql.append(" AND lower(json_extract_string(fields, ?)) = lower(?)");
            }
        }
        if ("duration".equals(sort)) {
            sql.append(" ORDER BY duration_us DESC NULLS LAST, ts DESC");
        } else {
            sql.append(" ORDER BY ts DESC");
        }
        sql.append(" LIMIT ?");
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setInt(index++, jvmId);
            statement.setString(index++, type);
            statement.setLong(index++, fromMs);
            statement.setLong(index++, toMs);
            if (fieldFilter) {
                if (!"thread".equals(field) && !"top_frame".equals(field)) {
                    statement.setString(index++, "$." + field);
                }
                statement.setString(index++, expected);
            }
            statement.setInt(index, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<EventRow> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(readEvent(rows));
                }
                return found;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public Map<String, RollupRow> readTotalsAt(int jvmId, long ts) throws IOException {
        String sql = """
                SELECT jvm_id, metric, by_name, key, ts, min, max, avg, count, sum
                FROM rollup_5s
                WHERE jvm_id = ? AND ts = ? AND by_name = '' AND key = ''
                """;
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setInt(1, jvmId);
            statement.setLong(2, ts);
            try (ResultSet rows = statement.executeQuery()) {
                Map<String, RollupRow> found = new LinkedHashMap<>();
                while (rows.next()) {
                    RollupRow row = mapRollup(rows);
                    found.put(row.metric(), row);
                }
                return found;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public void upsertIncident(IncidentRecord row) throws IOException {
        String sql = """
                INSERT INTO incidents (
                    id, jvm_id, type, group_name, level, status, t0, t1, peak_at, score, confidence,
                    headline, felt, when_json, how_bad, together, who, where_json, todo, sources, flags, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::JSON, ?::JSON, ?::JSON, ?::JSON, ?::JSON, ?::JSON, ?::JSON, ?::JSON, ?)
                ON CONFLICT (id) DO UPDATE SET
                    level = excluded.level, status = excluded.status, t1 = excluded.t1, peak_at = excluded.peak_at,
                    score = excluded.score, confidence = excluded.confidence, headline = excluded.headline,
                    felt = excluded.felt, when_json = excluded.when_json, how_bad = excluded.how_bad,
                    together = excluded.together, who = excluded.who, where_json = excluded.where_json,
                    todo = excluded.todo, sources = excluded.sources, flags = excluded.flags,
                    updated_at = excluded.updated_at
                """;
        synchronized (this) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, row.id());
                statement.setInt(2, row.jvmId());
                statement.setString(3, row.type());
                statement.setString(4, row.group());
                statement.setString(5, row.level());
                statement.setString(6, row.status());
                statement.setLong(7, row.t0());
                if (row.t1() == null) {
                    statement.setNull(8, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(8, row.t1());
                }
                statement.setLong(9, row.peakAt());
                statement.setDouble(10, row.score());
                statement.setString(11, row.confidence());
                statement.setString(12, row.headline());
                statement.setString(13, row.felt());
                statement.setString(14, row.whenJson());
                statement.setString(15, row.howBadJson());
                statement.setString(16, row.togetherJson());
                statement.setString(17, row.whoJson());
                statement.setString(18, row.whereJson());
                statement.setString(19, row.todoJson());
                statement.setString(20, row.sourcesJson());
                statement.setString(21, row.flagsJson());
                statement.setTimestamp(22, Timestamp.from(Instant.now()));
                statement.executeUpdate();
            } catch (SQLException error) {
                throw io(error);
            }
        }
    }

    public void addIncidentWindow(String id, int jvmId, String group, long ts) throws IOException {
        synchronized (this) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO incident_windows (incident_id, jvm_id, group_name, ts)
                    SELECT ?, ?, ?, ?
                    WHERE NOT EXISTS (
                        SELECT 1 FROM incident_windows WHERE incident_id = ? AND ts = ?)
                    """)) {
                statement.setString(1, id);
                statement.setInt(2, jvmId);
                statement.setString(3, group);
                statement.setLong(4, ts);
                statement.setString(5, id);
                statement.setLong(6, ts);
                statement.executeUpdate();
            } catch (SQLException error) {
                throw io(error);
            }
        }
    }

    public boolean incidentExists(String id) throws IOException {
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement("SELECT 1 FROM incidents WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public List<IncidentRecord> incidents(int jvmId) throws IOException {
        String sql = """
                SELECT id, jvm_id, type, group_name, level, status, t0, t1, peak_at, score, confidence,
                       headline, felt, when_json, how_bad, together, who, where_json, todo, sources, flags
                FROM incidents WHERE jvm_id = ?
                """;
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setInt(1, jvmId);
            try (ResultSet rows = statement.executeQuery()) {
                List<IncidentRecord> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(readIncident(rows));
                }
                return found;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public IncidentRecord incident(String id) throws IOException {
        String sql = """
                SELECT id, jvm_id, type, group_name, level, status, t0, t1, peak_at, score, confidence,
                       headline, felt, when_json, how_bad, together, who, where_json, todo, sources, flags
                FROM incidents WHERE id = ?
                """;
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return readIncident(rows);
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    private static EventRow readEvent(ResultSet rows) throws SQLException {
        return new EventRow(
                rows.getString(1),
                rows.getLong(2),
                nullableLong(rows, 3),
                rows.getString(4),
                rows.getString(5),
                rows.getString(6));
    }

    /** {@code wasNull} describes the column just read. Call it before the next getter. */
    private static Long nullableLong(ResultSet rows, int column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static IncidentRecord readIncident(ResultSet rows) throws SQLException {
        String id = rows.getString(1);
        int jvmId = rows.getInt(2);
        String type = rows.getString(3);
        String group = rows.getString(4);
        String level = rows.getString(5);
        String status = rows.getString(6);
        long t0 = rows.getLong(7);
        Long t1 = nullableLong(rows, 8);
        return new IncidentRecord(
                id,
                jvmId,
                type,
                group,
                level,
                status,
                t0,
                t1,
                rows.getLong(9),
                rows.getDouble(10),
                rows.getString(11),
                rows.getString(12),
                rows.getString(13),
                rows.getString(14),
                rows.getString(15),
                rows.getString(16),
                rows.getString(17),
                rows.getString(18),
                rows.getString(19),
                rows.getString(20),
                rows.getString(21));
    }

    private static int levelRank(String level) {
        if ("critical".equals(level)) {
            return 2;
        }
        if ("warning".equals(level)) {
            return 1;
        }
        return 0;
    }

    public List<long[]> windowBounds(int jvmId, long fromMs, long toMs) throws IOException {
        String sql = "SELECT ts, end_ts FROM windows WHERE jvm_id = ? AND ts >= ? AND ts < ? ORDER BY ts";
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setInt(1, jvmId);
            statement.setLong(2, fromMs);
            statement.setLong(3, toMs);
            try (ResultSet rows = statement.executeQuery()) {
                List<long[]> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new long[] {rows.getLong(1), rows.getLong(2)});
                }
                return found;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    private static List<JvmRow> readJvms(ResultSet rows) throws SQLException {
        List<JvmRow> found = new ArrayList<>();
        while (rows.next()) {
            found.add(new JvmRow(
                    rows.getInt(1),
                    rows.getString(2),
                    rows.getString(3),
                    rows.getString(4),
                    rows.getString(5),
                    rows.getString(6),
                    rows.getLong(7),
                    rows.getInt(8),
                    rows.getString(9),
                    rows.getString(10),
                    rows.getLong(11),
                    rows.getLong(12),
                    rows.getInt(13),
                    rows.getString(14)));
        }
        return found;
    }

    private static RollupRow mapRollup(ResultSet rows) throws SQLException {
        return new RollupRow(
                rows.getInt(1),
                rows.getString(2),
                rows.getString(3),
                rows.getString(4),
                rows.getLong(5),
                rows.getDouble(6),
                rows.getDouble(7),
                rows.getDouble(8),
                rows.getLong(9),
                rows.getDouble(10));
    }

    public int windowCount() throws IOException {
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement("SELECT COUNT(*) FROM windows");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public List<String> processedChunkIds() throws IOException {
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement("SELECT chunk_id FROM windows");
                ResultSet rows = statement.executeQuery()) {
            List<String> ids = new ArrayList<>();
            while (rows.next()) {
                ids.add(rows.getString(1));
            }
            return ids;
        } catch (SQLException error) {
            throw io(error);
        }
    }

    /** Deletes v2 rows whose window start is older than {@code horizonMs}. */
    public synchronized void purgeOlderThan(long horizonMs) throws IOException {
        boolean previous = true;
        try {
            previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            deleteTs("rollup_5s", horizonMs);
            deleteTs("rollup_1m", horizonMs);
            deleteTs("hist", horizonMs);
            deleteTs("events", horizonMs);
            deleteTs("internal_events", horizonMs);
            deleteTs("internal_series", horizonMs);
            deleteTs("samples", horizonMs);
            deleteTs("windows", horizonMs);
            deleteTs("incident_windows", horizonMs);
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM incidents WHERE t1 IS NOT NULL AND t1 < ?")) {
                statement.setLong(1, horizonMs);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM counter_cursor WHERE ts < ?")) {
                statement.setLong(1, horizonMs);
                statement.executeUpdate();
            }
            try (var statement = connection.createStatement()) {
                statement.execute("""
                        DELETE FROM stacks WHERE stack_id NOT IN (
                            SELECT stack_id FROM samples WHERE stack_id IS NOT NULL
                            UNION
                            SELECT stack_id FROM events WHERE stack_id IS NOT NULL
                            UNION
                            SELECT stack_id FROM internal_events WHERE stack_id IS NOT NULL)
                        """);
            }
            connection.commit();
        } catch (SQLException error) {
            rollbackQuietly();
            throw io(error);
        } finally {
            restoreAutoCommit(previous);
        }
    }

    private void applyCounters(Connection write, int jvmId, WindowData data, boolean late) throws SQLException {
        Map<String, List<WindowData.CounterObs>> grouped = new LinkedHashMap<>();
        for (WindowData.CounterObs obs : data.counters()) {
            grouped.computeIfAbsent(obs.metric() + "\0" + obs.byName() + "\0" + obs.key(), ignored -> new ArrayList<>())
                    .add(obs);
        }
        double dt = data.dtSeconds;
        for (List<WindowData.CounterObs> samples : grouped.values()) {
            WindowData.CounterObs last = samples.get(samples.size() - 1);
            if (samples.size() >= 2) {
                double first = samples.get(0).value();
                double value = last.value();
                saveCursor(write, jvmId, last, data.fromMs, value, late);
                if (!late && dt > 0d && value >= first) {
                    data.add(last.metric(), last.byName(), last.key(), (value - first) / dt);
                }
                continue;
            }
            Double previous = readCursor(write, jvmId, last);
            saveCursor(write, jvmId, last, data.fromMs, last.value(), late);
            if (late || previous == null || dt <= 0d || last.value() < previous) {
                continue;
            }
            data.add(last.metric(), last.byName(), last.key(), (last.value() - previous) / dt);
        }
    }

    private Double readCursor(Connection write, int jvmId, WindowData.CounterObs obs) throws SQLException {
        try (PreparedStatement statement = write.prepareStatement(
                "SELECT value FROM counter_cursor WHERE jvm_id = ? AND metric = ? AND ckey = ?")) {
            statement.setInt(1, jvmId);
            statement.setString(2, obs.metric());
            statement.setString(3, obs.byName() + "\0" + obs.key());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return rows.getDouble(1);
            }
        }
    }

    private void saveCursor(
            Connection write, int jvmId, WindowData.CounterObs obs, long ts, double value, boolean late)
            throws SQLException {
        if (late) {
            return;
        }
        try (PreparedStatement statement = write.prepareStatement("""
                INSERT INTO counter_cursor (jvm_id, metric, ckey, ts, value)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (jvm_id, metric, ckey) DO UPDATE SET ts = excluded.ts, value = excluded.value
                """)) {
            statement.setInt(1, jvmId);
            statement.setString(2, obs.metric());
            statement.setString(3, obs.byName() + "\0" + obs.key());
            statement.setLong(4, ts);
            statement.setDouble(5, value);
            statement.executeUpdate();
        }
    }

    private int upsertJvm(WindowData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT jvm_id FROM jvms
                WHERE cluster_id = ? AND namespace = ? AND pod_name = ? AND container_name = ? AND jvm_start_ms = ?
                """)) {
            statement.setString(1, data.clusterId);
            statement.setString(2, data.namespace);
            statement.setString(3, data.podName);
            statement.setString(4, data.containerName);
            statement.setLong(5, data.jvmStartMs);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return rows.getInt(1);
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO jvms (
                    jvm_id, cluster_id, namespace, pod_name, pod_uid, container_name, jvm_start_ms,
                    jdk_major, jdk_version, profile, first_ms, last_ms, windows, settings)
                VALUES (nextval('jvm_id_seq'), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?::JSON)
                RETURNING jvm_id
                """)) {
            statement.setString(1, data.clusterId);
            statement.setString(2, data.namespace);
            statement.setString(3, data.podName);
            statement.setString(4, data.podUid);
            statement.setString(5, data.containerName);
            statement.setLong(6, data.jvmStartMs);
            statement.setInt(7, data.jdkMajor);
            statement.setString(8, data.jdkVersion);
            statement.setString(9, data.profile);
            statement.setLong(10, data.fromMs);
            statement.setLong(11, data.toMs);
            statement.setString(12, data.settingsJson == null ? "{}" : data.settingsJson);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private void touchJvm(Connection write, int jvmId, WindowData data) throws SQLException {
        int major = data.jdkMajor;
        String version = data.jdkVersion;
        String profile = data.profile;
        String settings = data.settingsJson;
        try (PreparedStatement read = write.prepareStatement(
                "SELECT jdk_major, jdk_version, profile, settings FROM jvms WHERE jvm_id = ?")) {
            read.setInt(1, jvmId);
            try (ResultSet rows = read.executeQuery()) {
                if (rows.next()) {
                    if (major == 0) {
                        major = rows.getInt(1);
                    }
                    String storedVersion = rows.getString(2);
                    if ((version == null || version.isBlank()) && storedVersion != null && !storedVersion.isBlank()) {
                        version = storedVersion;
                    }
                    String storedProfile = rows.getString(3);
                    if ((profile == null || profile.isBlank()) && storedProfile != null && !storedProfile.isBlank()) {
                        profile = storedProfile;
                    }
                    settings = SettingsMerge.merge(rows.getString(4), settings);
                }
            }
        }
        try (PreparedStatement statement = write.prepareStatement("""
                UPDATE jvms
                SET jdk_major = ?, jdk_version = ?, profile = ?,
                    first_ms = LEAST(first_ms, ?), last_ms = GREATEST(last_ms, ?),
                    windows = (SELECT COUNT(*) FROM windows WHERE jvm_id = ?),
                    settings = ?::JSON
                WHERE jvm_id = ?
                """)) {
            statement.setInt(1, major);
            statement.setString(2, version);
            statement.setString(3, profile);
            statement.setLong(4, data.fromMs);
            statement.setLong(5, data.toMs);
            statement.setInt(6, jvmId);
            statement.setString(7, settings == null ? "{}" : settings);
            statement.setInt(8, jvmId);
            statement.executeUpdate();
        }
    }

    private void appendRollup(int jvmId, WindowData data) throws SQLException {
        try (DuckDBAppender appender = ((DuckDBConnection) connection).createAppender(
                DuckDBConnection.DEFAULT_SCHEMA, "rollup_5s")) {
            for (Map.Entry<WindowData.SeriesKey, WindowData.Accum> entry : data.series().entrySet()) {
                WindowData.Accum accum = entry.getValue();
                if (accum.count <= 0) {
                    continue;
                }
                WindowData.SeriesKey key = entry.getKey();
                appender.beginRow();
                appender.append(jvmId);
                appender.append(key.metric());
                appender.append(key.byName());
                appender.append(key.key());
                appender.append(data.fromMs);
                appender.append(accum.min);
                appender.append(accum.max);
                appender.append(accum.avg());
                appender.append(accum.count);
                appender.append(accum.sum);
                appender.endRow();
            }
        }
    }

    private void appendHist(int jvmId, WindowData data) throws SQLException {
        try (DuckDBAppender appender = ((DuckDBConnection) connection).createAppender(
                DuckDBConnection.DEFAULT_SCHEMA, "hist")) {
            for (Map.Entry<WindowData.HistKey, Long> entry : data.hist().entrySet()) {
                if (entry.getValue() == null || entry.getValue() <= 0) {
                    continue;
                }
                WindowData.HistKey key = entry.getKey();
                appender.beginRow();
                appender.append(jvmId);
                appender.append(key.metric());
                appender.append(key.key());
                appender.append(data.fromMs);
                appender.append((short) key.bucket());
                appender.append(entry.getValue());
                appender.endRow();
            }
        }
    }

    private void appendInternalEvents(int jvmId, WindowData data) throws SQLException {
        if (data.internalEvents().isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO internal_events (jvm_id, name, ts, duration_us, thread, stack_id, fields)
                VALUES (?, ?, ?, ?, ?, ?, ?::JSON)
                """)) {
            for (WindowData.StoredEvent event : data.internalEvents()) {
                statement.setInt(1, jvmId);
                statement.setString(2, event.type());
                statement.setLong(3, event.tsMs());
                if (event.durationUs() == null) {
                    statement.setNull(4, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(4, event.durationUs());
                }
                if (event.thread() == null) {
                    statement.setNull(5, java.sql.Types.VARCHAR);
                } else {
                    statement.setString(5, event.thread());
                }
                if (event.stackId() == null) {
                    statement.setNull(6, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(6, event.stackId());
                }
                statement.setString(7, event.fieldsJson() == null ? "{}" : event.fieldsJson());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void appendEvents(int jvmId, WindowData data) throws SQLException {
        try (DuckDBAppender appender = ((DuckDBConnection) connection).createAppender(
                DuckDBConnection.DEFAULT_SCHEMA, "events")) {
            for (WindowData.StoredEvent event : data.events()) {
                appender.beginRow();
                appender.append(jvmId);
                appender.append(event.type());
                appender.append(event.tsMs());
                if (event.durationUs() == null) {
                    appender.appendNull();
                } else {
                    appender.append(event.durationUs());
                }
                appender.append(event.thread());
                if (event.topFrame() == null) {
                    appender.appendNull();
                } else {
                    appender.append(event.topFrame());
                }
                if (event.stackId() == null) {
                    appender.appendNull();
                } else {
                    appender.append(event.stackId());
                }
                appender.append(event.fieldsJson());
                appender.endRow();
            }
        }
    }

    private void insertStacks(WindowData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO stacks (stack_id, frames) VALUES (?, ?::JSON)
                ON CONFLICT (stack_id) DO NOTHING
                """)) {
            for (Map.Entry<Long, String> entry : data.stacks().entrySet()) {
                statement.setLong(1, entry.getKey());
                statement.setString(2, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public void writeInternalSeries(int jvmId, String name, String key, long ts, double value) throws IOException {
        synchronized (this) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO internal_series (jvm_id, name, key, ts, value) VALUES (?, ?, ?, ?, ?)")) {
                statement.setInt(1, jvmId);
                statement.setString(2, name);
                statement.setString(3, key == null ? "" : key);
                statement.setLong(4, ts);
                statement.setDouble(5, value);
                statement.executeUpdate();
            } catch (SQLException error) {
                throw io(error);
            }
        }
    }

    public List<TimedValue> readInternalPoints(int jvmId, String name, String key, long fromMs, long toMs) throws IOException {
        String sql = """
                SELECT ts, value FROM internal_series
                WHERE jvm_id = ? AND name = ? AND key = ? AND ts >= ? AND ts < ?
                ORDER BY ts
                """;
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setInt(1, jvmId);
            statement.setString(2, name);
            statement.setString(3, key == null ? "" : key);
            statement.setLong(4, fromMs);
            statement.setLong(5, toMs);
            try (ResultSet rows = statement.executeQuery()) {
                List<TimedValue> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new TimedValue(rows.getLong(1), rows.getDouble(2)));
                }
                return found;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    private void appendInternalSeries(int jvmId, WindowData data) throws SQLException {
        if (data.internalSeries().isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO internal_series (jvm_id, name, key, ts, value) VALUES (?, ?, ?, ?, ?)")) {
            for (WindowData.SeriesPoint point : data.internalSeries()) {
                statement.setInt(1, jvmId);
                statement.setString(2, point.name());
                statement.setString(3, point.key());
                statement.setLong(4, data.fromMs);
                statement.setDouble(5, point.value());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void appendSamples(int jvmId, WindowData data) throws SQLException {
        try (DuckDBAppender appender = ((DuckDBConnection) connection).createAppender(
                DuckDBConnection.DEFAULT_SCHEMA, "samples")) {
            for (Map.Entry<WindowData.SampleKey, WindowData.SampleAccum> entry : data.samples().entrySet()) {
                WindowData.SampleKey key = entry.getKey();
                appender.beginRow();
                appender.append(jvmId);
                appender.append(data.fromMs);
                appender.append(key.kind());
                appender.append(key.stackId());
                appender.append(key.thread());
                appender.append(key.state());
                appender.append(key.handler());
                appender.append(entry.getValue().count);
                appender.append(entry.getValue().weight);
                appender.append(key.lockClass());
                appender.endRow();
            }
        }
    }

    private void rebuildMinute(int jvmId, long fromMs) throws SQLException {
        long minute = Math.floorDiv(fromMs, 60_000L) * 60_000L;
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM rollup_1m WHERE jvm_id = ? AND ts = ?")) {
            delete.setInt(1, jvmId);
            delete.setLong(2, minute);
            delete.executeUpdate();
        }
        String sql = """
                SELECT metric, by_name, key, MIN(min), MAX(max), SUM(count), SUM(sum)
                FROM rollup_5s
                WHERE jvm_id = ? AND ts >= ? AND ts < ?
                GROUP BY metric, by_name, key
                ORDER BY metric, by_name, key
                """;
        List<Object[]> merged = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, jvmId);
            statement.setLong(2, minute);
            statement.setLong(3, minute + 60_000L);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    merged.add(new Object[] {
                        rows.getString(1),
                        rows.getString(2),
                        rows.getString(3),
                        rows.getDouble(4),
                        rows.getDouble(5),
                        rows.getLong(6),
                        rows.getDouble(7)
                    });
                }
            }
        }
        try (DuckDBAppender appender = ((DuckDBConnection) connection).createAppender(
                DuckDBConnection.DEFAULT_SCHEMA, "rollup_1m")) {
            for (Object[] row : merged) {
                long count = (Long) row[5];
                double sum = (Double) row[6];
                appender.beginRow();
                appender.append(jvmId);
                appender.append((String) row[0]);
                appender.append((String) row[1]);
                appender.append((String) row[2]);
                appender.append(minute);
                appender.append((Double) row[3]);
                appender.append((Double) row[4]);
                appender.append(count == 0 ? 0d : sum / count);
                appender.append(count);
                appender.append(sum);
                appender.endRow();
            }
        }
    }

    private static void insertWindow(Connection write, int jvmId, String chunkId, WindowData data, boolean late)
            throws SQLException {
        try (PreparedStatement statement = write.prepareStatement("""
                INSERT INTO windows (jvm_id, ts, end_ts, chunk_id, processed_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setInt(1, jvmId);
            statement.setLong(2, data.fromMs);
            statement.setLong(3, data.toMs);
            statement.setString(4, chunkId);
            statement.setTimestamp(5, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
        if (late) {
            LOG.debugf("stored late window %s", chunkId);
        }
    }

    private static boolean isLate(Connection write, int jvmId, long ts) throws SQLException {
        try (PreparedStatement statement = write.prepareStatement(
                "SELECT MAX(ts) FROM windows WHERE jvm_id = ?")) {
            statement.setInt(1, jvmId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return false;
                }
                long max = rows.getLong(1);
                return !rows.wasNull() && ts < max;
            }
        }
    }

    private static boolean windowExists(Connection write, int jvmId, long ts) throws SQLException {
        try (PreparedStatement statement = write.prepareStatement(
                "SELECT 1 FROM windows WHERE jvm_id = ? AND ts = ?")) {
            statement.setInt(1, jvmId);
            statement.setLong(2, ts);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static boolean chunkProcessed(Connection write, String chunkId) throws SQLException {
        try (PreparedStatement statement = write.prepareStatement(
                "SELECT 1 FROM windows WHERE chunk_id = ?")) {
            statement.setString(1, chunkId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static void deletePartial(Connection write, int jvmId, long fromMs, long toMs) throws SQLException {
        for (String table : List.of("rollup_5s", "hist", "events", "samples")) {
            try (PreparedStatement statement = write.prepareStatement(
                    "DELETE FROM " + table + " WHERE jvm_id = ? AND ts = ?")) {
                statement.setInt(1, jvmId);
                statement.setLong(2, fromMs);
                statement.executeUpdate();
            }
        }
        try (PreparedStatement statement = write.prepareStatement(
                "DELETE FROM internal_events WHERE jvm_id = ? AND ts >= ? AND ts < ?")) {
            statement.setInt(1, jvmId);
            statement.setLong(2, fromMs);
            statement.setLong(3, toMs);
            statement.executeUpdate();
        }
    }

    private void deleteTs(String table, long horizonMs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE ts < ?")) {
            statement.setLong(1, horizonMs);
            statement.executeUpdate();
        }
    }

    private List<RollupRow> rollup(String table) throws IOException {
        String sql = "SELECT jvm_id, metric, by_name, key, ts, min, max, avg, count, sum FROM " + table
                + " ORDER BY jvm_id, metric, by_name, key, ts";
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            List<RollupRow> found = new ArrayList<>();
            while (rows.next()) {
                found.add(new RollupRow(
                        rows.getInt(1),
                        rows.getString(2),
                        rows.getString(3),
                        rows.getString(4),
                        rows.getLong(5),
                        rows.getDouble(6),
                        rows.getDouble(7),
                        rows.getDouble(8),
                        rows.getLong(9),
                        rows.getDouble(10)));
            }
            return found;
        } catch (SQLException error) {
            throw io(error);
        }
    }

    private void createSchema() throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE SEQUENCE IF NOT EXISTS jvm_id_seq START 1");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS jvms (
                        jvm_id INTEGER PRIMARY KEY,
                        cluster_id VARCHAR,
                        namespace VARCHAR,
                        pod_name VARCHAR,
                        pod_uid VARCHAR,
                        container_name VARCHAR,
                        jvm_start_ms BIGINT,
                        jdk_major INTEGER,
                        jdk_version VARCHAR,
                        profile VARCHAR,
                        first_ms BIGINT,
                        last_ms BIGINT,
                        windows INTEGER,
                        settings JSON)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS windows (
                        jvm_id INTEGER,
                        ts BIGINT,
                        end_ts BIGINT,
                        chunk_id VARCHAR,
                        processed_at TIMESTAMP,
                        PRIMARY KEY (jvm_id, ts))
                    """);
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS windows_chunk ON windows(chunk_id)");
            statement.execute(rollupTable("rollup_5s"));
            statement.execute(rollupTable("rollup_1m"));
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS hist (
                        jvm_id INTEGER,
                        metric VARCHAR,
                        key VARCHAR NOT NULL DEFAULT '',
                        ts BIGINT,
                        bucket SMALLINT,
                        count BIGINT)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS events (
                        jvm_id INTEGER,
                        type VARCHAR,
                        ts BIGINT,
                        duration_us BIGINT,
                        thread VARCHAR,
                        top_frame VARCHAR,
                        stack_id BIGINT,
                        fields JSON)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS internal_events (
                        jvm_id INTEGER,
                        name VARCHAR,
                        ts BIGINT,
                        duration_us BIGINT,
                        thread VARCHAR,
                        stack_id BIGINT,
                        fields JSON)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS stacks (
                        stack_id BIGINT PRIMARY KEY,
                        frames JSON)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS samples (
                        jvm_id INTEGER,
                        ts BIGINT,
                        kind VARCHAR,
                        stack_id BIGINT,
                        thread VARCHAR,
                        state VARCHAR,
                        handler VARCHAR,
                        count BIGINT,
                        weight DOUBLE,
                        lock_class VARCHAR)
                    """);
            statement.execute("ALTER TABLE samples ADD COLUMN IF NOT EXISTS lock_class VARCHAR");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS internal_series (
                        jvm_id INTEGER,
                        name VARCHAR,
                        key VARCHAR NOT NULL DEFAULT '',
                        ts BIGINT,
                        value DOUBLE)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS incidents (
                        id VARCHAR PRIMARY KEY,
                        jvm_id INTEGER,
                        type VARCHAR,
                        group_name VARCHAR,
                        level VARCHAR,
                        status VARCHAR,
                        t0 BIGINT,
                        t1 BIGINT,
                        peak_at BIGINT,
                        score DOUBLE,
                        confidence VARCHAR,
                        headline VARCHAR,
                        felt VARCHAR,
                        when_json JSON,
                        how_bad JSON,
                        together JSON,
                        who JSON,
                        where_json JSON,
                        todo JSON,
                        sources JSON,
                        flags JSON,
                        updated_at TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS incident_windows (
                        incident_id VARCHAR,
                        jvm_id INTEGER,
                        group_name VARCHAR,
                        ts BIGINT)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS counter_cursor (
                        jvm_id INTEGER,
                        metric VARCHAR,
                        ckey VARCHAR,
                        ts BIGINT,
                        value DOUBLE,
                        PRIMARY KEY (jvm_id, metric, ckey))
                    """);
        }
    }

    private static String rollupTable(String name) {
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    jvm_id INTEGER,
                    metric VARCHAR,
                    by_name VARCHAR DEFAULT '',
                    key VARCHAR DEFAULT '',
                    ts BIGINT,
                    min DOUBLE,
                    max DOUBLE,
                    avg DOUBLE,
                    count BIGINT,
                    sum DOUBLE)
                """.formatted(name);
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:duckdb:" + databaseFile.toAbsolutePath());
    }

    private Connection readConnection() throws SQLException {
        synchronized (this) {
            if (connection == null || connection.isClosed()) {
                throw new SQLException("v2 store is closed");
            }
            return ((DuckDBConnection) connection).duplicate();
        }
    }

    private void renameBroken() throws IOException {
        long stamp = System.currentTimeMillis();
        Path broken = databaseFile.resolveSibling(DATABASE_FILE + ".broken-" + stamp);
        if (Files.exists(databaseFile)) {
            Files.move(databaseFile, broken, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Path wal = databaseFile.resolveSibling(DATABASE_FILE + ".wal");
        if (Files.exists(wal)) {
            Files.move(wal, databaseFile.resolveSibling(DATABASE_FILE + ".wal.broken-" + stamp));
        }
    }

    private void closeQuietly() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // The replacement connection is opened next.
        }
        connection = null;
    }

    private void rollbackQuietly() {
        try {
            if (connection != null && !connection.getAutoCommit()) {
                connection.rollback();
            }
        } catch (SQLException ignored) {
            // The original failure is the one returned to the caller.
        }
    }

    private void restoreAutoCommit(boolean previous) {
        try {
            if (connection != null) {
                connection.setAutoCommit(previous);
            }
        } catch (SQLException ignored) {
            // The connection stays usable for the next synchronized write.
        }
    }

    @PreDestroy
    public synchronized void close() {
        closeQuietly();
    }

    private static IOException io(SQLException error) {
        return new IOException(error.getMessage(), error);
    }

    public record RollupRow(
            int jvmId,
            String metric,
            String byName,
            String key,
            long ts,
            double min,
            double max,
            double avg,
            long count,
            double sum) {}

    public record JvmRow(
            int jvmId,
            String clusterId,
            String namespace,
            String podName,
            String podUid,
            String containerName,
            long jvmStartMs,
            int jdkMajor,
            String jdkVersion,
            String profile,
            long firstMs,
            long lastMs,
            int windows,
            String settings) {}

    public record HistRow(int jvmId, String metric, String key, long ts, int bucket, long count) {}

    public record EventRow(
            String type, long ts, Long durationUs, String thread, String topFrame, String fields) {}

    public record TimedValue(long ts, double value) {}

    public record SampleRow(
            long ts,
            String kind,
            long stackId,
            String thread,
            String state,
            String handler,
            long count,
            double weight,
            String lockClass) {
        public SampleRow(
                long ts, String kind, long stackId, String thread, String state, String handler, long count, double weight) {
            this(ts, kind, stackId, thread, state, handler, count, weight, null);
        }
    }

    public record IncidentRecord(
            String id,
            int jvmId,
            String type,
            String group,
            String level,
            String status,
            long t0,
            Long t1,
            long peakAt,
            double score,
            String confidence,
            String headline,
            String felt,
            String whenJson,
            String howBadJson,
            String togetherJson,
            String whoJson,
            String whereJson,
            String todoJson,
            String sourcesJson,
            String flagsJson) {}

    public record BaselineSlot(long ts, boolean present, double min, double max, double avg, long count, double sum) {}

    public record CommitResult(boolean wrote, int jvmId, boolean late) {
        public static final CommitResult SKIPPED = new CommitResult(false, -1, false);
    }
}

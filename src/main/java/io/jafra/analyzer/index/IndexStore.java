package io.jafra.analyzer.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.duckdb.DuckDBConnection;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Embedded DuckDB index for stored chunks. One write connection is owned here. Every write method
 * is synchronized. Reads use {@link DuckDBConnection#duplicate()} so they do not share that
 * connection's statement state.
 */
@ApplicationScoped
public class IndexStore {
    static final String DATABASE_FILE = "index.duckdb";

    private static final String COLUMNS = """
            chunk_id, recording_id, cluster_id, node_name, namespace, pod_name, pod_uid,
            container_name, jvm_instance, physical_file, file_sequence, chunk_sequence,
            chunk_offset, chunk_length, start_ns, end_ns, min_event_start_ns, max_event_end_ns,
            checksum, path, status, received_at, decoded_at
            """;

    private final Path databaseFile;
    private Connection connection;
    private boolean rebuildPending;

    @Inject
    public IndexStore(
            @ConfigProperty(name = "jafra.storage.root", defaultValue = "/var/lib/jafra/analyzer") String root) {
        this(Path.of(root));
    }

    public IndexStore(Path storageRoot) {
        this.databaseFile = storageRoot.resolve("data").resolve(DATABASE_FILE);
        try {
            Files.createDirectories(databaseFile.getParent());
            Class.forName("org.duckdb.DuckDBDriver");
            boolean missing = !Files.exists(databaseFile);
            try {
                this.connection = connect();
                createSchema();
                this.rebuildPending = missing;
            } catch (SQLException openError) {
                closeQuietly();
                renameBroken();
                this.connection = connect();
                createSchema();
                this.rebuildPending = true;
            }
        } catch (IOException | SQLException | ClassNotFoundException error) {
            throw new IllegalStateException("unable to open chunk index " + databaseFile, error);
        }
    }

    /** True once, when this open created a new database or replaced an unreadable one. */
    public synchronized boolean consumeRebuild() {
        boolean pending = rebuildPending;
        rebuildPending = false;
        return pending;
    }

    public Path databaseFile() {
        return databaseFile;
    }

    public synchronized boolean exists(String chunkId) throws IOException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM chunks WHERE chunk_id = ?")) {
            statement.setString(1, chunkId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    /** Inserts a pending row. Returns false when chunk_id is already present. */
    public synchronized boolean insertPending(ChunkIndexRow row) throws IOException {
        String sql = """
                INSERT INTO chunks (
                    chunk_id, recording_id, cluster_id, node_name, namespace, pod_name, pod_uid,
                    container_name, jvm_instance, physical_file, file_sequence, chunk_sequence,
                    chunk_offset, chunk_length, start_ns, end_ns, min_event_start_ns, max_event_end_ns,
                    checksum, path, status, received_at, decoded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindInsert(statement, row);
            statement.executeUpdate();
            return true;
        } catch (SQLException error) {
            if (isPrimaryKeyConflict(error)) {
                return false;
            }
            throw io(error);
        }
    }

    public synchronized void markStored(String chunkId) throws IOException {
        updateStatus(chunkId, ChunkIndexRow.STATUS_STORED);
    }

    public synchronized void markDeleting(long olderThanNs) throws IOException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE chunks SET status = ? WHERE status = ? AND max_event_end_ns < ?")) {
            statement.setString(1, ChunkIndexRow.STATUS_DELETING);
            statement.setString(2, ChunkIndexRow.STATUS_STORED);
            statement.setLong(3, olderThanNs);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw io(error);
        }
    }

    /**
     * Widens the event window. A later start or an earlier end leaves the stored bounds in place.
     * Also stamps decoded_at.
     */
    public synchronized void updateEventRange(String chunkId, long minStartNs, long maxEndNs) throws IOException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chunks
                SET min_event_start_ns = LEAST(min_event_start_ns, ?),
                    max_event_end_ns = GREATEST(max_event_end_ns, ?),
                    decoded_at = CURRENT_TIMESTAMP
                WHERE chunk_id = ?
                """)) {
            statement.setLong(1, minStartNs);
            statement.setLong(2, maxEndNs);
            statement.setString(3, chunkId);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw io(error);
        }
    }

    /** Sets decoded_at without changing the event window. Used when a chunk cannot be parsed. */
    public synchronized void markDecoded(String chunkId) throws IOException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE chunks SET decoded_at = CURRENT_TIMESTAMP WHERE chunk_id = ?")) {
            statement.setString(1, chunkId);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public synchronized void delete(String chunkId) throws IOException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM chunks WHERE chunk_id = ?")) {
            statement.setString(1, chunkId);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public synchronized void setStorageVersion(int version, long migrated, long quarantined, long dropped)
            throws IOException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM schema_version");
                PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO schema_version (storage_version, migrated, quarantined, dropped, migrated_at)
                        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """)) {
            delete.executeUpdate();
            insert.setInt(1, version);
            insert.setLong(2, migrated);
            insert.setLong(3, quarantined);
            insert.setLong(4, dropped);
            insert.executeUpdate();
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public ChunkIndexRow get(String chunkId) throws IOException {
        String sql = "SELECT " + COLUMNS + " FROM chunks WHERE chunk_id = ?";
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setString(1, chunkId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return mapRow(rows);
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public List<ChunkIndexRow> findRange(
            String cluster, String namespace, String pod, String container, long fromNs, long toNs)
            throws IOException {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM chunks WHERE cluster_id = ? AND status = ?");
        sql.append(" AND min_event_start_ns < ? AND max_event_end_ns > ?");
        List<String> filters = new ArrayList<>();
        if (present(namespace)) {
            sql.append(" AND namespace = ?");
            filters.add(namespace);
        }
        if (present(pod)) {
            sql.append(" AND pod_name = ?");
            filters.add(pod);
        }
        if (present(container)) {
            sql.append(" AND container_name = ?");
            filters.add(container);
        }
        sql.append(" ORDER BY start_ns");
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql.toString())) {
            statement.setString(1, cluster);
            statement.setString(2, ChunkIndexRow.STATUS_STORED);
            statement.setLong(3, toNs);
            statement.setLong(4, fromNs);
            for (int i = 0; i < filters.size(); i++) {
                statement.setString(5 + i, filters.get(i));
            }
            return readRows(statement);
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public List<ChunkIndexRow> storedRows() throws IOException {
        String sql = "SELECT " + COLUMNS + " FROM chunks WHERE status = ? ORDER BY start_ns, chunk_id";
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setString(1, ChunkIndexRow.STATUS_STORED);
            return readRows(statement);
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public List<ChunkIndexRow> storedByRecording(String recordingId) throws IOException {
        String sql = "SELECT " + COLUMNS + " FROM chunks WHERE recording_id = ? AND status = ? ORDER BY chunk_offset";
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setString(1, recordingId);
            statement.setString(2, ChunkIndexRow.STATUS_STORED);
            return readRows(statement);
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public List<String> distinctNamespaces(String cluster) throws IOException {
        return distinct("namespace", "SELECT DISTINCT namespace FROM chunks WHERE cluster_id = ? AND status = ? ORDER BY namespace", cluster);
    }

    public List<String> distinctPods(String cluster, String namespace) throws IOException {
        return distinct(
                "pod_name",
                "SELECT DISTINCT pod_name FROM chunks WHERE cluster_id = ? AND namespace = ? AND status = ? ORDER BY pod_name",
                cluster,
                namespace);
    }

    public List<String> distinctContainers(String cluster, String namespace, String pod) throws IOException {
        return distinct(
                "container_name",
                """
                SELECT DISTINCT container_name FROM chunks
                WHERE cluster_id = ? AND namespace = ? AND pod_name = ? AND status = ?
                ORDER BY container_name
                """,
                cluster,
                namespace,
                pod);
    }

    public List<ChunkIndexRow> pendingRows() throws IOException {
        return rowsByStatus(ChunkIndexRow.STATUS_PENDING);
    }

    public List<ChunkIndexRow> deletingRows() throws IOException {
        return rowsByStatus(ChunkIndexRow.STATUS_DELETING);
    }

    public List<String> undecodedChunkIds() throws IOException {
        String sql = "SELECT chunk_id FROM chunks WHERE status = ? AND decoded_at IS NULL ORDER BY chunk_id";
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setString(1, ChunkIndexRow.STATUS_STORED);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (rows.next()) {
                    ids.add(rows.getString(1));
                }
                return ids;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public int storedCount() throws IOException {
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(
                        "SELECT COUNT(*) FROM chunks WHERE status = ?")) {
            statement.setString(1, ChunkIndexRow.STATUS_STORED);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    public StorageVersion storageVersion() throws IOException {
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement("""
                        SELECT storage_version, migrated, quarantined, dropped, migrated_at
                        FROM schema_version
                        """);
                ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return null;
            }
            Timestamp migratedAt = rows.getTimestamp(5);
            return new StorageVersion(
                    rows.getInt(1),
                    rows.getLong(2),
                    rows.getLong(3),
                    rows.getLong(4),
                    migratedAt == null ? null : migratedAt.toInstant());
        } catch (SQLException error) {
            throw io(error);
        }
    }

    @PreDestroy
    public synchronized void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Shutdown still drops the process-wide lock on the next open attempt.
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:duckdb:" + databaseFile.toAbsolutePath());
    }

    private void renameBroken() throws IOException {
        long stamp = System.currentTimeMillis();
        moveIfPresent(databaseFile, databaseFile.resolveSibling(DATABASE_FILE + ".broken-" + stamp));
        moveIfPresent(
                databaseFile.resolveSibling(DATABASE_FILE + ".wal"),
                databaseFile.resolveSibling(DATABASE_FILE + ".wal.broken-" + stamp));
    }

    private static void moveIfPresent(Path source, Path target) throws IOException {
        if (Files.exists(source)) {
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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

    private void createSchema() throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        storage_version INTEGER NOT NULL,
                        migrated BIGINT,
                        quarantined BIGINT,
                        dropped BIGINT,
                        migrated_at TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS chunks (
                        chunk_id VARCHAR PRIMARY KEY,
                        recording_id VARCHAR NOT NULL,
                        cluster_id VARCHAR NOT NULL,
                        node_name VARCHAR,
                        namespace VARCHAR NOT NULL,
                        pod_name VARCHAR NOT NULL,
                        pod_uid VARCHAR NOT NULL,
                        container_name VARCHAR NOT NULL,
                        jvm_instance VARCHAR NOT NULL DEFAULT 'default',
                        physical_file VARCHAR,
                        file_sequence UINTEGER,
                        chunk_sequence UINTEGER,
                        chunk_offset UBIGINT,
                        chunk_length UBIGINT NOT NULL,
                        start_ns BIGINT NOT NULL,
                        end_ns BIGINT NOT NULL,
                        min_event_start_ns BIGINT NOT NULL,
                        max_event_end_ns BIGINT NOT NULL,
                        checksum VARCHAR NOT NULL,
                        path VARCHAR NOT NULL,
                        status VARCHAR NOT NULL,
                        received_at TIMESTAMP NOT NULL,
                        decoded_at TIMESTAMP)
                    """);
        }
    }

    private void updateStatus(String chunkId, String status) throws IOException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE chunks SET status = ? WHERE chunk_id = ?")) {
            statement.setString(1, status);
            statement.setString(2, chunkId);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw io(error);
        }
    }

    private List<ChunkIndexRow> rowsByStatus(String status) throws IOException {
        String sql = "SELECT " + COLUMNS + " FROM chunks WHERE status = ? ORDER BY chunk_id";
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            statement.setString(1, status);
            return readRows(statement);
        } catch (SQLException error) {
            throw io(error);
        }
    }

    private List<String> distinct(String column, String sql, String... args) throws IOException {
        try (Connection read = readConnection();
                PreparedStatement statement = read.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setString(i + 1, args[i]);
            }
            statement.setString(args.length + 1, ChunkIndexRow.STATUS_STORED);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (rows.next()) {
                    values.add(rows.getString(column));
                }
                return values;
            }
        } catch (SQLException error) {
            throw io(error);
        }
    }

    private Connection readConnection() throws SQLException {
        synchronized (this) {
            if (connection == null || connection.isClosed()) {
                throw new SQLException("index is closed");
            }
            return ((DuckDBConnection) connection).duplicate();
        }
    }

    private static void bindInsert(PreparedStatement statement, ChunkIndexRow row) throws SQLException {
        statement.setString(1, row.chunkId());
        statement.setString(2, row.recordingId());
        statement.setString(3, row.clusterId());
        setNullableString(statement, 4, row.nodeName());
        statement.setString(5, row.namespace());
        statement.setString(6, row.podName());
        statement.setString(7, row.podUid());
        statement.setString(8, row.containerName());
        statement.setString(9, row.jvmInstance());
        setNullableString(statement, 10, row.physicalFile());
        setNullableInt(statement, 11, row.fileSequence());
        setNullableInt(statement, 12, row.chunkSequence());
        setNullableLong(statement, 13, row.chunkOffset());
        statement.setLong(14, row.chunkLength());
        statement.setLong(15, row.startNs());
        statement.setLong(16, row.endNs());
        statement.setLong(17, row.minEventStartNs());
        statement.setLong(18, row.maxEventEndNs());
        statement.setString(19, row.checksum());
        statement.setString(20, row.path());
        statement.setString(21, ChunkIndexRow.STATUS_PENDING);
        Instant received = row.receivedAt() == null ? Instant.now() : row.receivedAt();
        statement.setTimestamp(22, Timestamp.from(received));
    }

    private static List<ChunkIndexRow> readRows(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            List<ChunkIndexRow> found = new ArrayList<>();
            while (rows.next()) {
                found.add(mapRow(rows));
            }
            return found;
        }
    }

    private static ChunkIndexRow mapRow(ResultSet rows) throws SQLException {
        Timestamp received = rows.getTimestamp("received_at");
        Timestamp decoded = rows.getTimestamp("decoded_at");
        return new ChunkIndexRow(
                rows.getString("chunk_id"),
                rows.getString("recording_id"),
                rows.getString("cluster_id"),
                rows.getString("node_name"),
                rows.getString("namespace"),
                rows.getString("pod_name"),
                rows.getString("pod_uid"),
                rows.getString("container_name"),
                rows.getString("jvm_instance"),
                rows.getString("physical_file"),
                nullableInt(rows, "file_sequence"),
                nullableInt(rows, "chunk_sequence"),
                nullableLong(rows, "chunk_offset"),
                rows.getLong("chunk_length"),
                rows.getLong("start_ns"),
                rows.getLong("end_ns"),
                rows.getLong("min_event_start_ns"),
                rows.getLong("max_event_end_ns"),
                rows.getString("checksum"),
                rows.getString("path"),
                rows.getString("status"),
                received == null ? null : received.toInstant(),
                decoded == null ? null : decoded.toInstant());
    }

    private static Integer nullableInt(ResultSet rows, String column) throws SQLException {
        Object value = rows.getObject(column);
        if (value == null) {
            return null;
        }
        return ((Number) value).intValue();
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        Object value = rows.getObject(column);
        if (value == null) {
            return null;
        }
        return ((Number) value).longValue();
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isPrimaryKeyConflict(SQLException error) {
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("duplicate key") || lower.contains("primary key");
    }

    private static IOException io(SQLException error) {
        return new IOException(error.getMessage(), error);
    }
}

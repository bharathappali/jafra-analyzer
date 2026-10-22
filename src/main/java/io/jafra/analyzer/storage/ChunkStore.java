package io.jafra.analyzer.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jafra.analyzer.index.ChunkIndexRow;
import io.jafra.analyzer.index.IndexStore;
import io.jafra.analyzer.index.StorageVersion;
import io.jafra.analyzer.storage.legacy.LegacyMigrator;
import io.jafra.analyzer.storage.legacy.LegacyMigrator.Checkpoint;

public class ChunkStore {
    private static final Logger LOG = Logger.getLogger(ChunkStore.class);
    private static final long HEADROOM_BYTES = 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path root;
    private final Path tmpDir;
    private final Path dataDir;
    private final Path identitiesDir;
    private final Path partDir;
    private final IndexStore index;
    private final boolean ownsIndex;
    private volatile boolean ready = true;
    private volatile Consumer<String> afterStored = chunkId -> {};
    private volatile Runnable rescan = () -> {};
    private volatile Checkpoint migrationCheckpoint = phase -> {};
    private volatile CrashPoint crashPoint = step -> {};
    private final AtomicBoolean sidecarRepairStarted = new AtomicBoolean();
    private final Map<String, String> podNames = new ConcurrentHashMap<>();

    public ChunkStore(Path root) {
        this(root, new IndexStore(root), true);
    }

    public ChunkStore(Path root, IndexStore index) {
        this(root, index, false);
    }

    private ChunkStore(Path root, IndexStore index, boolean ownsIndex) {
        this.root = root;
        this.tmpDir = root.resolve("tmp");
        this.partDir = root.resolve("data").resolve("tmp");
        this.dataDir = root.resolve("data");
        this.identitiesDir = root.resolve("identities");
        this.index = index;
        this.ownsIndex = ownsIndex;
    }

    public IndexStore index() {
        return index;
    }

    public boolean ready() {
        return ready;
    }

    /** Ingest answers RETRY while this is false. Startup clears it when migration finishes. */
    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public void onStored(Consumer<String> afterStored) {
        this.afterStored = afterStored == null ? chunkId -> {} : afterStored;
    }

    public void onRescan(Runnable rescan) {
        this.rescan = rescan == null ? () -> {} : rescan;
    }

    public void setMigrationCheckpoint(Checkpoint checkpoint) {
        this.migrationCheckpoint = checkpoint == null ? phase -> {} : checkpoint;
    }

    public void setCrashPoint(CrashPoint crashPoint) {
        this.crashPoint = crashPoint == null ? step -> {} : crashPoint;
    }

    public void close() {
        if (ownsIndex) {
            index.close();
        }
    }

    public Path root() {
        return root;
    }

    /** Fast recovery plus a synchronous migration when one is still required. */
    public void recover() throws IOException {
        recoverFast();
        if (!ready) {
            runMigration(migrationCheckpoint);
        }
    }

    /**
     * Directory setup, index rebuild, and pending/deleting reconciliation. Does not migrate legacy
     * layouts. {@link #ready()} is false when a migration still has to run.
     */
    public void recoverFast() throws IOException {
        Files.createDirectories(tmpDir);
        Files.createDirectories(partDir);
        Files.createDirectories(dataDir);
        Files.createDirectories(root.resolve("data").resolve("recordings"));
        Files.createDirectories(root.resolve("data").resolve("quarantine"));
        Files.createDirectories(identitiesDir);
        deleteTmpParts();
        deleteJsonTmp();
        if (index.consumeRebuild()) {
            rebuildFromSidecars();
        }
        reconcilePending();
        finishDeleting();
        podNames.clear();
        loadIdentities();
        ready = !needsMigration();
        if (ready && index.storageVersion() == null) {
            index.setStorageVersion(2, 0, 0, 0);
        }
        if (ready) {
            rescan.run();
        }
        LOG.infof("recovered %d durable chunks under %s", index.storedCount(), root);
    }

    public void runMigration(Checkpoint checkpoint) throws IOException {
        if (!needsMigration()) {
            ready = true;
            return;
        }
        ready = false;
        Checkpoint hook = checkpoint == null ? phase -> {} : checkpoint;
        LegacyMigrator.Result result = new LegacyMigrator(hook).migrate(root, index, Map.copyOf(podNames));
        index.setStorageVersion(2, result.migrated(), result.quarantined(), result.dropped());
        ready = true;
        LOG.infof(
                "storage migration complete version=2 migrated=%d quarantined=%d dropped=%d",
                result.migrated(),
                result.quarantined(),
                result.dropped());
        rescan.run();
    }

    public void startSidecarRepair() {
        if (!sidecarRepairStarted.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(this::repairMissingSidecars, "jafra-sidecar-repair");
        thread.setDaemon(true);
        thread.start();
    }

    public void repairMissingSidecars() {
        if (!ready) {
            LOG.debugf("skipping sidecar repair until storage is ready");
            return;
        }
        try {
            for (ChunkIndexRow row : index.storedRows()) {
                synchronized (SidecarLocks.of(row.chunkId())) {
                    ChunkIndexRow current = index.get(row.chunkId());
                    if (current == null || !ChunkIndexRow.STATUS_STORED.equals(current.status())) {
                        continue;
                    }
                    if (current.path() == null || current.path().isBlank()) {
                        continue;
                    }
                    Path payload = recordedPayload(current);
                    if (!Files.isRegularFile(payload)) {
                        continue;
                    }
                    Path sidecar = ChunkSidecar.beside(payload);
                    if (!Files.isRegularFile(sidecar)) {
                        ChunkSidecar.write(sidecar, ChunkSidecar.fromRow(current));
                    }
                }
            }
        } catch (IOException error) {
            LOG.warnf(error, "sidecar repair failed");
        }
    }

    public boolean contains(String chunkId) {
        try {
            return index.exists(chunkId);
        } catch (IOException error) {
            LOG.warnf(error, "index lookup failed for %s", chunkId);
            return false;
        }
    }

    public int durableChunkCount() {
        try {
            return index.storedCount();
        } catch (IOException error) {
            LOG.warnf(error, "index count failed");
            return 0;
        }
    }

    /** Bytes of durable chunk payloads. */
    public long chunksBytes() {
        long total = 0;
        try {
            for (ChunkIndexRow row : index.storedRows()) {
                if (row.path() == null || row.path().isBlank()) {
                    continue;
                }
                Path payload = dataDir.resolve("recordings").resolve(row.path());
                if (Files.isRegularFile(payload)) {
                    total += Files.size(payload);
                }
            }
        } catch (IOException ignored) {
            // A missing file is not counted.
        }
        return total;
    }

    /**
     * @deprecated Prefer {@link #chunksBytes()}. Status JSON still has {@code stitchedBytes} and always reports 0.
     */
    @Deprecated
    public long stitchedBytes() {
        return 0;
    }

    public boolean hasRoom(long chunkLength) {
        try {
            return Files.getFileStore(root).getUsableSpace() > chunkLength + HEADROOM_BYTES;
        } catch (IOException error) {
            return true;
        }
    }

    public IncomingWrite beginWrite(String chunkId) throws IOException {
        if (chunkId == null || chunkId.isBlank() || chunkId.indexOf('/') >= 0 || chunkId.indexOf('\\') >= 0
                || chunkId.contains("..")) {
            throw new InvalidIdentityException("chunk_id is not a single path segment");
        }
        Files.createDirectories(partDir);
        Path part = partDir.resolve(chunkId + ".part");
        Files.deleteIfExists(part);
        FileChannel channel = FileChannel.open(
                part,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        return new IncomingWrite(chunkId, part, channel);
    }

    public void commit(IncomingWrite incoming, ChunkMetadata metadata) throws IOException {
        commit(incoming, metadata, null, null, null);
    }

    public void commit(
            IncomingWrite incoming,
            ChunkMetadata metadata,
            String nodeName,
            Integer fileSequence,
            Integer chunkSequence)
            throws IOException {
        incoming.force();
        incoming.closeQuietly();
        final Path payload;
        final Path sidecar;
        final String relative;
        try {
            payload = ChunkPaths.payload(root, metadata);
            sidecar = ChunkPaths.sidecar(root, metadata);
            relative = ChunkPaths.relative(metadata);
        } catch (RuntimeException error) {
            deleteQuietly(incoming.part);
            throw error;
        }
        long startNs = metadata.chunkStartTimeNs();
        long endNs = startNs + Math.max(0, metadata.chunkDurationNs());
        String cluster = metadata.clusterId() == null || metadata.clusterId().isBlank()
                ? "default"
                : metadata.clusterId();
        ChunkIndexRow row = new ChunkIndexRow(
                metadata.chunkId(),
                metadata.recordingId(),
                cluster,
                nodeName,
                metadata.namespace(),
                metadata.podName(),
                metadata.podUid(),
                metadata.containerName(),
                ChunkIndexRow.JVM_INSTANCE_DEFAULT,
                metadata.physicalFilename(),
                fileSequence,
                chunkSequence,
                metadata.chunkOffset(),
                metadata.chunkLength(),
                startNs,
                endNs,
                startNs,
                endNs,
                metadata.checksum() == null ? "" : metadata.checksum(),
                relative,
                ChunkIndexRow.STATUS_PENDING,
                null,
                null);
        if (!index.insertPending(row)) {
            deleteQuietly(incoming.part);
            throw new DuplicateChunkException(metadata.chunkId());
        }
        try {
            crash("insert");
            Files.createDirectories(payload.getParent());
            ChunkSidecar.write(
                    sidecar,
                    ChunkSidecar.from(
                            metadata,
                            nodeName,
                            fileSequence,
                            chunkSequence,
                            ChunkIndexRow.JVM_INSTANCE_DEFAULT,
                            startNs,
                            endNs));
            crash("json");
            DurableFiles.moveAtomic(incoming.part, payload);
            crash("payload");
            index.markStored(metadata.chunkId());
            crash("stored");
        } catch (IOException error) {
            index.delete(metadata.chunkId());
            deleteQuietly(payload);
            deleteQuietly(sidecar);
            deleteQuietly(incoming.part);
            throw error;
        }
        try {
            afterStored.accept(metadata.chunkId());
        } catch (Throwable error) {
            LOG.warnf(error, "decode hook failed for %s", metadata.chunkId());
        }
        rememberPodName(metadata.namespace(), metadata.podUid(), metadata.podName());
    }

    private void crash(String step) {
        crashPoint.after(step);
    }

    public void abort(IncomingWrite incoming) {
        if (incoming != null) {
            incoming.abort();
        }
    }

    /** Payload path recorded on the index row. Does not query the index again. */
    public Path recordedPayload(ChunkIndexRow row) {
        return root.resolve("data").resolve("recordings").resolve(row.path());
    }

    public Path payloadPath(String chunkId) throws IOException {
        ChunkIndexRow row = index.get(chunkId);
        if (row == null || row.path() == null || row.path().isBlank()) {
            throw new NoSuchFileException("no stored payload for " + chunkId);
        }
        return recordedPayload(row);
    }

    /** Contiguous chunks from offset 0 for a physical recording (holes stop the prefix). */
    public List<ChunkMetadata> contiguousChunks(String recordingId) {
        List<ChunkMetadata> indexed = contiguousFromIndex(recordingId);
        return indexed == null ? List.of() : indexed;
    }

    public RecordingManifest manifest(String recordingId) {
        RecordingManifest manifest = new RecordingManifest();
        for (ChunkMetadata chunk : contiguousChunks(recordingId)) {
            manifest.chunkIds.add(chunk.chunkId());
            manifest.nextOffset += chunk.chunkLength();
        }
        manifest.stitchedBytes = manifest.nextOffset;
        return manifest;
    }

    public boolean hasContiguousPrefix(String recordingId) {
        return !contiguousChunks(recordingId).isEmpty();
    }

    /**
     * Payload paths in the order a stitch used to write them: each recording id, then that
     * recording's contiguous chunks from offset 0. A hole stops the prefix.
     */
    public List<Path> contiguousPayloads(List<String> recordingIds) throws IOException {
        List<Path> paths = new ArrayList<>();
        for (String recordingId : recordingIds) {
            List<ChunkMetadata> contiguous = contiguousChunks(recordingId);
            if (contiguous.isEmpty()) {
                throw new IOException("no contiguous chunks for " + recordingId);
            }
            for (ChunkMetadata chunk : contiguous) {
                paths.add(payloadPath(chunk.chunkId()));
            }
        }
        return List.copyOf(paths);
    }

    public void rememberPodName(String namespace, String podUid, String podName) {
        if (podUid == null || podUid.isBlank() || podName == null || podName.isBlank()) {
            return;
        }
        String previous = podNames.put(podUid, podName);
        if (podName.equals(previous)) {
            return;
        }
        try {
            Files.createDirectories(identitiesDir);
            DurableFiles.writeAtomic(
                    identitiesDir.resolve(safe(podUid) + ".json"),
                    MAPPER.writeValueAsBytes(new WorkloadIdentity(namespace, podName, podUid)));
        } catch (IOException error) {
            LOG.warnf(error, "unable to persist pod name for %s", podUid);
        }
    }

    private List<ChunkMetadata> contiguousFromIndex(String recordingId) {
        try {
            TreeMap<Long, ChunkMetadata> chunks = new TreeMap<>();
            for (ChunkIndexRow row : index.storedByRecording(recordingId)) {
                long offset = row.chunkOffset() == null ? 0L : row.chunkOffset();
                chunks.put(offset, metadataFrom(row));
            }
            if (chunks.isEmpty()) {
                return null;
            }
            List<ChunkMetadata> contiguous = new ArrayList<>();
            long nextOffset = 0;
            while (true) {
                ChunkMetadata next = chunks.get(nextOffset);
                if (next == null) {
                    break;
                }
                contiguous.add(next);
                nextOffset += next.chunkLength();
            }
            return contiguous;
        } catch (IOException error) {
            LOG.warnf(error, "index recording lookup failed for %s", recordingId);
            return null;
        }
    }

    private static ChunkMetadata metadataFrom(ChunkIndexRow row) {
        long duration = Math.max(0, row.endNs() - row.startNs());
        return new ChunkMetadata(
                row.chunkId(),
                row.recordingId(),
                row.clusterId(),
                row.namespace(),
                row.podUid(),
                row.podName(),
                row.containerName(),
                row.physicalFile() == null ? "" : row.physicalFile(),
                row.chunkOffset() == null ? 0L : row.chunkOffset(),
                row.chunkLength(),
                row.checksum(),
                row.startNs(),
                duration);
    }

    private boolean needsMigration() throws IOException {
        if (LegacyMigrator.hasLegacy(root)) {
            return true;
        }
        StorageVersion version = index.storageVersion();
        return version != null && version.storageVersion() != 2;
    }

    private void rebuildFromSidecars() throws IOException {
        Path recordings = root.resolve("data").resolve("recordings");
        if (!Files.isDirectory(recordings)) {
            return;
        }
        try (Stream<Path> files = Files.walk(recordings)) {
            for (Path json : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                rebuildOne(recordings, json);
            }
        }
    }

    private void rebuildOne(Path recordings, Path json) {
        try {
            ChunkSidecar.Document document = ChunkSidecar.read(json);
            ChunkMetadata metadata = ChunkSidecar.metadata(document);
            if (metadata.chunkId() == null || metadata.chunkId().isBlank()) {
                LOG.warnf("skipping sidecar without a chunk id: %s", json);
                return;
            }
            String name = json.getFileName().toString();
            Path payload = json.resolveSibling(name.substring(0, name.length() - ".json".length()) + ".jfr");
            if (!Files.isRegularFile(payload)) {
                LOG.warnf("skipping sidecar without a payload: %s", json);
                return;
            }
            String relative = recordings.relativize(payload).toString().replace('\\', '/');
            long startNs = metadata.chunkStartTimeNs();
            long endNs = startNs + Math.max(0, metadata.chunkDurationNs());
            long min = document.minEventStartNs == null ? startNs : document.minEventStartNs;
            long max = document.maxEventEndNs == null ? endNs : document.maxEventEndNs;
            String cluster = metadata.clusterId() == null || metadata.clusterId().isBlank()
                    ? "default"
                    : metadata.clusterId();
            String jvm = document.jvmInstance == null || document.jvmInstance.isBlank()
                    ? ChunkIndexRow.JVM_INSTANCE_DEFAULT
                    : document.jvmInstance;
            ChunkIndexRow row = new ChunkIndexRow(
                    metadata.chunkId(),
                    metadata.recordingId(),
                    cluster,
                    document.nodeName,
                    metadata.namespace(),
                    metadata.podName(),
                    metadata.podUid(),
                    metadata.containerName(),
                    jvm,
                    metadata.physicalFilename(),
                    document.fileSequence,
                    document.chunkSequence,
                    metadata.chunkOffset(),
                    metadata.chunkLength(),
                    startNs,
                    endNs,
                    min,
                    max,
                    metadata.checksum() == null ? "" : metadata.checksum(),
                    relative,
                    ChunkIndexRow.STATUS_PENDING,
                    null,
                    null);
            if (index.insertPending(row)) {
                index.markStored(metadata.chunkId());
            }
        } catch (IOException error) {
            LOG.warnf(error, "skipping unreadable sidecar %s", json);
        }
    }

    private void reconcilePending() throws IOException {
        Path recordings = root.resolve("data").resolve("recordings");
        for (ChunkIndexRow row : index.pendingRows()) {
            Path payload = recordings.resolve(row.path() == null || row.path().isBlank() ? "" : row.path());
            Path sidecar = ChunkSidecar.beside(payload);
            if (Files.isRegularFile(payload)) {
                if (!Files.isRegularFile(sidecar)) {
                    ChunkSidecar.write(sidecar, ChunkSidecar.fromRow(row));
                }
                index.markStored(row.chunkId());
            } else {
                deleteQuietly(sidecar);
                index.delete(row.chunkId());
            }
        }
    }

    public void purgeExpired(java.time.Duration retention) throws IOException {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            return;
        }
        long nowNs = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());
        index.markDeleting(nowNs - retention.toNanos());
        finishDeleting();
    }

    private void finishDeleting() throws IOException {
        Path recordings = root.resolve("data").resolve("recordings");
        for (ChunkIndexRow row : index.deletingRows()) {
            synchronized (SidecarLocks.of(row.chunkId())) {
                ChunkIndexRow current = index.get(row.chunkId());
                if (current == null || !ChunkIndexRow.STATUS_DELETING.equals(current.status())) {
                    continue;
                }
                if (current.path() != null && !current.path().isBlank()) {
                    Path payload = recordings.resolve(current.path());
                    deleteQuietly(payload);
                    String name = payload.getFileName().toString();
                    String stem = name.endsWith(".jfr") ? name.substring(0, name.length() - 4) : name;
                    deleteQuietly(payload.resolveSibling(stem + ".json"));
                    pruneEmptyDirectories(payload.getParent());
                }
                index.delete(current.chunkId());
            }
        }
    }

    private void pruneEmptyDirectories(Path start) throws IOException {
        Path stop = root.resolve("data").resolve("recordings");
        Path current = start;
        while (current != null && current.startsWith(stop) && !current.equals(stop)) {
            if (!Files.isDirectory(current)) {
                current = current.getParent();
                continue;
            }
            try (Stream<Path> children = Files.list(current)) {
                if (children.findAny().isPresent()) {
                    return;
                }
            }
            Files.deleteIfExists(current);
            current = current.getParent();
        }
    }

    private void deleteTmpParts() throws IOException {
        deleteParts(tmpDir);
        deleteParts(partDir);
    }

    private void deleteJsonTmp() throws IOException {
        deleteJsonTmp(root.resolve("data").resolve("recordings"));
        deleteJsonTmp(root.resolve("data").resolve("quarantine"));
    }

    private static void deleteJsonTmp(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(path -> isJsonTmp(path.getFileName().toString())).toList()) {
                deleteQuietly(file);
            }
        }
    }

    private static boolean isJsonTmp(String name) {
        return name.endsWith(".json.tmp") || name.contains(".json.tmp.");
    }

    private static void deleteParts(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> parts = Files.list(dir)) {
            parts.filter(path -> path.getFileName().toString().endsWith(".part")).forEach(ChunkStore::deleteQuietly);
        }
    }

    private void loadIdentities() throws IOException {
        if (!Files.exists(identitiesDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(identitiesDir)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                try {
                    WorkloadIdentity identity = MAPPER.readValue(path.toFile(), WorkloadIdentity.class);
                    if (identity.podUid() != null && identity.podName() != null && !identity.podName().isBlank()) {
                        podNames.putIfAbsent(identity.podUid(), identity.podName());
                    }
                } catch (IOException error) {
                    LOG.warnf(error, "skipping unreadable identity %s", path);
                }
            });
        }
    }

    static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "_";
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.equals(".") || cleaned.equals("..")) {
            return "_";
        }
        return cleaned;
    }

    public static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    public final class IncomingWrite {
        private final String chunkId;
        private final Path part;
        private final FileChannel channel;

        IncomingWrite(String chunkId, Path part, FileChannel channel) {
            this.chunkId = chunkId;
            this.part = part;
            this.channel = channel;
        }

        public void write(byte[] payload) throws IOException {
            channel.write(ByteBuffer.wrap(payload));
        }

        public void force() throws IOException {
            channel.force(true);
        }

        public void closeQuietly() {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }

        public void abort() {
            closeQuietly();
            deleteQuietly(part);
        }

        public String chunkId() {
            return chunkId;
        }
    }

    @FunctionalInterface
    public interface CrashPoint {
        void after(String step);
    }

    public record WorkloadIdentity(
            @JsonProperty("namespace") String namespace,
            @JsonProperty("podName") String podName,
            @JsonProperty("podUid") String podUid) {
    }
}

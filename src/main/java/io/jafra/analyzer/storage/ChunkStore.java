package io.jafra.analyzer.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ChunkStore {
    private static final Logger LOG = Logger.getLogger(ChunkStore.class);
    private static final long HEADROOM_BYTES = 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path root;
    private final Path tmpDir;
    private final Path chunksDir;
    private final Path recordingsDir;
    private final Path identitiesDir;
    private final Map<String, ChunkMetadata> committed = new ConcurrentHashMap<>();
    private final Map<String, TreeMap<Long, ChunkMetadata>> byRecording = new ConcurrentHashMap<>();
    private final Map<String, String> podNames = new ConcurrentHashMap<>();
    private final Map<String, Object> recordingLocks = new ConcurrentHashMap<>();

    public ChunkStore(Path root) {
        this.root = root;
        this.tmpDir = root.resolve("tmp");
        this.chunksDir = root.resolve("chunks");
        this.recordingsDir = root.resolve("recordings");
        this.identitiesDir = root.resolve("identities");
    }

    public void recover() throws IOException {
        Files.createDirectories(tmpDir);
        Files.createDirectories(chunksDir);
        Files.createDirectories(recordingsDir);
        Files.createDirectories(identitiesDir);
        deleteOrphans();
        committed.clear();
        byRecording.clear();
        podNames.clear();
        loadIdentities();
        try (Stream<Path> files = Files.list(chunksDir)) {
            files.filter(path -> path.getFileName().toString().endsWith(".meta"))
                    .forEach(this::loadMetaQuietly);
        }
        for (String recordingId : byRecording.keySet()) {
            stitch(recordingId);
        }
        LOG.infof("recovered %d durable chunks under %s", committed.size(), root);
    }

    public boolean contains(String chunkId) {
        return committed.containsKey(chunkId);
    }

    public Set<String> committedIds() {
        return Set.copyOf(committed.keySet());
    }

    public int durableChunkCount() {
        return committed.size();
    }

    public long stitchedBytes() {
        return byRecording.keySet().stream()
                .map(this::recordingDir)
                .map(dir -> dir.resolve("stitched.jfr"))
                .mapToLong(path -> {
                    try {
                        return Files.exists(path) ? Files.size(path) : 0;
                    } catch (IOException error) {
                        return 0;
                    }
                })
                .sum();
    }

    public boolean hasRoom(long chunkLength) {
        try {
            return Files.getFileStore(root).getUsableSpace() > chunkLength + HEADROOM_BYTES;
        } catch (IOException error) {
            return true;
        }
    }

    public IncomingWrite beginWrite(String chunkId) throws IOException {
        Path part = tmpDir.resolve(chunkId + ".part");
        Files.deleteIfExists(part);
        FileChannel channel = FileChannel.open(
                part,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        return new IncomingWrite(chunkId, part, channel);
    }

    public void commit(IncomingWrite incoming, ChunkMetadata metadata) throws IOException {
        incoming.force();
        incoming.closeQuietly();
        Path payload = payloadPath(metadata.chunkId());
        Files.move(incoming.part, payload, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        writeAtomic(metaPath(metadata.chunkId()), MAPPER.writeValueAsBytes(metadata));
        committed.put(metadata.chunkId(), metadata);
        byRecording.computeIfAbsent(metadata.recordingId(), ignored -> new TreeMap<>())
                .put(metadata.chunkOffset(), metadata);
        rememberPodName(metadata.namespace(), metadata.podUid(), metadata.podName());
        try {
            stitch(metadata.recordingId());
        } catch (IOException error) {
            LOG.warnf(error, "stitch lagged for %s; recover will rebuild", metadata.recordingId());
        }
    }

    public void abort(IncomingWrite incoming) {
        if (incoming != null) {
            incoming.abort();
        }
    }

    public Path stitchedFile(String recordingId) {
        return recordingDir(recordingId).resolve("stitched.jfr");
    }

    public Path recordingDirectory(String recordingId) {
        return recordingDir(recordingId);
    }

    public Set<String> recordingIds() {
        return Set.copyOf(byRecording.keySet());
    }

    public ChunkMetadata sample(String recordingId) {
        TreeMap<Long, ChunkMetadata> chunks = byRecording.get(recordingId);
        if (chunks == null || chunks.isEmpty()) {
            return null;
        }
        return chunks.firstEntry().getValue();
    }

    public Collection<ChunkMetadata> chunks(String recordingId) {
        TreeMap<Long, ChunkMetadata> chunks = byRecording.get(recordingId);
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        return List.copyOf(chunks.values());
    }

    public RecordingManifest manifest(String recordingId) {
        try {
            return readManifest(recordingDir(recordingId).resolve("manifest.json"));
        } catch (IOException error) {
            return new RecordingManifest();
        }
    }

    public String podName(String podUid, String stored) {
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        return podNames.getOrDefault(podUid, "");
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
            writeAtomic(
                    identitiesDir.resolve(safe(podUid) + ".json"),
                    MAPPER.writeValueAsBytes(new WorkloadIdentity(namespace, podName, podUid)));
        } catch (IOException error) {
            LOG.warnf(error, "unable to persist pod name for %s", podUid);
        }
    }

    private void stitch(String recordingId) throws IOException {
        Object lock = recordingLocks.computeIfAbsent(recordingId, ignored -> new Object());
        synchronized (lock) {
            TreeMap<Long, ChunkMetadata> chunks = byRecording.get(recordingId);
            if (chunks == null || chunks.isEmpty()) {
                return;
            }
            Path dir = recordingDir(recordingId);
            Files.createDirectories(dir);
            Path stitched = dir.resolve("stitched.jfr");
            Path manifestPath = dir.resolve("manifest.json");
            RecordingManifest manifest = readManifest(manifestPath);
            if (!Files.exists(stitched)) {
                Files.createFile(stitched);
                manifest = new RecordingManifest();
            }
            try (FileChannel out = FileChannel.open(stitched, StandardOpenOption.WRITE)) {
                long size = out.size();
                if (size != manifest.nextOffset) {
                    out.truncate(Math.min(size, manifest.nextOffset));
                }
                out.position(manifest.nextOffset);
                boolean wrote = false;
                while (true) {
                    ChunkMetadata next = chunks.get(manifest.nextOffset);
                    if (next == null) {
                        break;
                    }
                    try (FileChannel in = FileChannel.open(payloadPath(next.chunkId()), StandardOpenOption.READ)) {
                        long copied = 0;
                        while (copied < next.chunkLength()) {
                            long n = in.transferTo(copied, next.chunkLength() - copied, out);
                            if (n <= 0) {
                                throw new IOException("short read while stitching " + next.chunkId());
                            }
                            copied += n;
                        }
                    }
                    if (!manifest.chunkIds.contains(next.chunkId())) {
                        manifest.chunkIds.add(next.chunkId());
                    }
                    manifest.nextOffset += next.chunkLength();
                    manifest.stitchedBytes = manifest.nextOffset;
                    wrote = true;
                }
                if (wrote) {
                    out.force(true);
                }
            }
            writeAtomic(manifestPath, MAPPER.writeValueAsBytes(manifest));
            if (manifest.stitchedBytes > 0) {
                LOG.infof(
                        "{\"event\":\"jfr_recording_stitched\",\"recording_id\":\"%s\",\"stitched_bytes\":%d,\"chunks\":%d}",
                        recordingId,
                        manifest.stitchedBytes,
                        manifest.chunkIds.size());
            }
        }
    }

    private RecordingManifest readManifest(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new RecordingManifest();
        }
        RecordingManifest manifest = MAPPER.readValue(path.toFile(), RecordingManifest.class);
        if (manifest.chunkIds == null) {
            manifest.chunkIds = new java.util.ArrayList<>();
        }
        return manifest;
    }

    private void loadMetaQuietly(Path meta) {
        try {
            ChunkMetadata metadata = MAPPER.readValue(meta.toFile(), ChunkMetadata.class);
            if (metadata.chunkId() == null || !Files.exists(payloadPath(metadata.chunkId()))) {
                Files.deleteIfExists(meta);
                return;
            }
            committed.put(metadata.chunkId(), metadata);
            byRecording.computeIfAbsent(metadata.recordingId(), ignored -> new TreeMap<>())
                    .put(metadata.chunkOffset(), metadata);
            if (metadata.podName() != null && !metadata.podName().isBlank()) {
                podNames.putIfAbsent(metadata.podUid(), metadata.podName());
            }
        } catch (IOException error) {
            LOG.warnf(error, "skipping unreadable chunk metadata %s", meta);
        }
    }

    private void deleteOrphans() throws IOException {
        if (Files.exists(tmpDir)) {
            try (Stream<Path> parts = Files.list(tmpDir)) {
                parts.filter(path -> path.getFileName().toString().endsWith(".part")).forEach(ChunkStore::deleteQuietly);
            }
        }
        if (!Files.exists(chunksDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(chunksDir)) {
            files.filter(path -> path.getFileName().toString().endsWith(".jfr")).forEach(payload -> {
                Path meta = chunksDir.resolve(payload.getFileName().toString().replaceFirst("\\.jfr$", ".meta"));
                if (!Files.exists(meta)) {
                    deleteQuietly(payload);
                }
            });
        }
    }

    private Path payloadPath(String chunkId) {
        return chunksDir.resolve(chunkId + ".jfr");
    }

    private Path metaPath(String chunkId) {
        return chunksDir.resolve(chunkId + ".meta");
    }

    private Path recordingDir(String recordingId) {
        String[] parts = recordingId.split("/");
        Path dir = recordingsDir;
        for (String part : parts) {
            dir = dir.resolve(safe(part));
        }
        return dir;
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

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(
                tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteQuietly(Path path) {
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

    public record WorkloadIdentity(
            @JsonProperty("namespace") String namespace,
            @JsonProperty("podName") String podName,
            @JsonProperty("podUid") String podUid) {
    }
}

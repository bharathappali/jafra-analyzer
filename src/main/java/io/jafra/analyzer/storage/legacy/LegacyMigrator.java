package io.jafra.analyzer.storage.legacy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.jafra.analyzer.index.ChunkIndexRow;
import io.jafra.analyzer.index.IndexStore;
import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkPaths;
import io.jafra.analyzer.storage.ChunkStore;
import io.jafra.analyzer.storage.InvalidIdentityException;
import io.jafra.analyzer.storage.JfrHeaderTimes;

/**
 * One-way move from the v0.0.1, v0.0.2, and dev bucket layouts into data/recordings.
 * There is no downgrade back to those layouts.
 */
public final class LegacyMigrator {
    private static final Logger LOG = Logger.getLogger(LegacyMigrator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Checkpoint checkpoint;
    private long migrated;
    private long quarantined;
    private long dropped;

    public LegacyMigrator() {
        this(phase -> {});
    }

    public LegacyMigrator(Checkpoint checkpoint) {
        this.checkpoint = checkpoint == null ? phase -> {} : checkpoint;
    }

    public static boolean hasLegacy(Path root) throws IOException {
        if (Files.isDirectory(root.resolve("chunks")) || Files.isDirectory(root.resolve("meta"))) {
            return true;
        }
        if (Files.isDirectory(root.resolve("stitch-cache"))) {
            return true;
        }
        if (containsNamed(root.resolve("recordings"), "stitched.jfr")) {
            return true;
        }
        Path data = root.resolve("data");
        if (!Files.isDirectory(data)) {
            return false;
        }
        try (Stream<Path> children = Files.list(data)) {
            return children.anyMatch(path -> Files.isDirectory(path) && isHexBucket(path.getFileName().toString()));
        }
    }

    public Result migrate(Path root, IndexStore index, Map<String, String> podNames) throws IOException {
        Files.createDirectories(root.resolve("data").resolve("quarantine"));
        migrateV0(root, index, podNames);
        migrateV1(root, index, podNames);
        quarantineOrphanPayloads(root);
        cleanup(root);
        return new Result(migrated, quarantined, dropped);
    }

    private void migrateV0(Path root, IndexStore index, Map<String, String> podNames) throws IOException {
        Path chunks = root.resolve("chunks");
        if (!Files.isDirectory(chunks)) {
            return;
        }
        List<Path> metas;
        try (Stream<Path> files = Files.list(chunks)) {
            metas = files.filter(path -> path.getFileName().toString().endsWith(".meta")).toList();
        }
        for (Path meta : metas) {
            ChunkMetadata metadata = readMeta(meta);
            if (metadata == null || metadata.chunkId() == null || metadata.chunkId().isBlank()) {
                Path payload = siblingPayload(chunks, meta);
                quarantine(root, chunkIdFrom(meta), "bad meta", meta, payload);
                continue;
            }
            accept(root, index, podNames, metadata, chunks.resolve(metadata.chunkId() + ".jfr"), meta);
        }
    }

    private void migrateV1(Path root, IndexStore index, Map<String, String> podNames) throws IOException {
        Path metaRoot = root.resolve("meta");
        if (!Files.isDirectory(metaRoot)) {
            return;
        }
        List<Path> buckets;
        try (Stream<Path> children = Files.list(metaRoot)) {
            buckets = children.filter(path -> Files.isDirectory(path) && isHexBucket(path.getFileName().toString()))
                    .toList();
        }
        for (Path bucket : buckets) {
            List<Path> metas;
            try (Stream<Path> files = Files.list(bucket)) {
                metas = files.filter(Files::isRegularFile).toList();
            }
            for (Path meta : metas) {
                ChunkMetadata metadata = readMeta(meta);
                Path payload = root.resolve("data").resolve(bucket.getFileName()).resolve(meta.getFileName());
                if (metadata == null || metadata.chunkId() == null || metadata.chunkId().isBlank()) {
                    quarantine(root, chunkIdFrom(meta), "bad meta", meta, payload);
                    continue;
                }
                accept(root, index, podNames, metadata, payload, meta);
            }
        }
    }

    private void accept(
            Path root,
            IndexStore index,
            Map<String, String> podNames,
            ChunkMetadata metadata,
            Path payload,
            Path meta) throws IOException {
        if (!Files.isRegularFile(payload)) {
            ChunkStore.deleteQuietly(meta);
            dropped++;
            LOG.infof("dropped legacy meta %s because the payload is missing", meta);
            return;
        }
        ChunkMetadata filled = fill(metadata, payload, podNames);
        String reason = invalidReason(filled);
        if (reason != null) {
            quarantine(root, filled.chunkId(), reason, meta, payload);
            return;
        }
        if (Files.size(payload) != filled.chunkLength()) {
            quarantine(root, filled.chunkId(), "payload size does not match chunk_length", meta, payload);
            return;
        }
        ChunkIndexRow existing = index.get(filled.chunkId());
        Path target = ChunkPaths.payload(root, filled);
        if (existing != null) {
            resume(root, index, existing, filled, payload, meta, target);
            return;
        }
        long startNs = filled.chunkStartTimeNs();
        long endNs = startNs + Math.max(0, filled.chunkDurationNs());
        ChunkIndexRow row = new ChunkIndexRow(
                filled.chunkId(),
                filled.recordingId(),
                filled.clusterId(),
                null,
                filled.namespace(),
                filled.podName(),
                filled.podUid(),
                filled.containerName(),
                ChunkIndexRow.JVM_INSTANCE_DEFAULT,
                filled.physicalFilename(),
                null,
                null,
                filled.chunkOffset(),
                filled.chunkLength(),
                startNs,
                endNs,
                startNs,
                endNs,
                filled.checksum() == null ? "" : filled.checksum(),
                ChunkPaths.relative(filled),
                ChunkIndexRow.STATUS_PENDING,
                null,
                null);
        if (!index.insertPending(row)) {
            resume(root, index, index.get(filled.chunkId()), filled, payload, meta, target);
            return;
        }
        checkpoint.reach("before-move:" + filled.chunkId());
        place(root, index, filled, payload, meta, target);
        migrated++;
    }

    private void resume(
            Path root,
            IndexStore index,
            ChunkIndexRow existing,
            ChunkMetadata filled,
            Path payload,
            Path meta,
            Path target) throws IOException {
        if (existing == null) {
            quarantine(root, filled.chunkId(), "legacy copy does not match the indexed chunk", meta, payload);
            return;
        }
        boolean targetReady = Files.isRegularFile(target) && Files.size(target) == Files.size(payload);
        if (targetReady) {
            ChunkStore.deleteQuietly(payload);
            ChunkStore.deleteQuietly(meta);
            if (!ChunkIndexRow.STATUS_STORED.equals(existing.status())) {
                index.markStored(existing.chunkId());
            }
            return;
        }
        boolean pending = ChunkIndexRow.STATUS_PENDING.equals(existing.status());
        if ((pending || ChunkIndexRow.STATUS_STORED.equals(existing.status()))
                && !Files.isRegularFile(target)
                && Files.size(payload) == existing.chunkLength()) {
            checkpoint.reach("before-move:" + filled.chunkId());
            place(root, index, filled, payload, meta, target);
            migrated++;
            return;
        }
        if (Files.size(payload) == existing.chunkLength()) {
            ChunkStore.deleteQuietly(payload);
            ChunkStore.deleteQuietly(meta);
            return;
        }
        quarantine(root, filled.chunkId(), "legacy copy does not match the indexed chunk", meta, payload);
    }

    private void place(Path root, IndexStore index, ChunkMetadata filled, Path payload, Path meta, Path target)
            throws IOException {
        Files.createDirectories(target.getParent());
        if (!Files.isRegularFile(target)) {
            Files.move(payload, target, StandardCopyOption.ATOMIC_MOVE);
        } else {
            ChunkStore.deleteQuietly(payload);
        }
        writeAtomic(ChunkPaths.sidecar(root, filled), MAPPER.writeValueAsBytes(filled));
        index.markStored(filled.chunkId());
        ChunkStore.deleteQuietly(meta);
    }

    private static ChunkMetadata fill(ChunkMetadata metadata, Path payload, Map<String, String> podNames) {
        long startNs = metadata.chunkStartTimeNs();
        long durationNs = metadata.chunkDurationNs();
        if (startNs <= 0) {
            long[] times = JfrHeaderTimes.read(payload);
            if (times != null) {
                startNs = times[0];
                durationNs = times[1];
            }
        }
        String pod = metadata.podName();
        if (pod == null || pod.isBlank()) {
            String known = podNames == null ? null : podNames.get(metadata.podUid());
            pod = known == null ? "" : known;
        }
        String cluster = metadata.clusterId() == null || metadata.clusterId().isBlank() ? "default" : metadata.clusterId();
        return new ChunkMetadata(
                metadata.chunkId(),
                metadata.recordingId() == null ? "" : metadata.recordingId(),
                cluster,
                metadata.namespace(),
                metadata.podUid(),
                pod,
                metadata.containerName(),
                metadata.physicalFilename(),
                metadata.chunkOffset(),
                metadata.chunkLength(),
                metadata.checksum(),
                startNs,
                durationNs);
    }

    private static String invalidReason(ChunkMetadata metadata) {
        if (metadata.recordingId() == null || metadata.recordingId().isBlank()) {
            return "missing recording id";
        }
        if (metadata.podName() == null || metadata.podName().isBlank()) {
            return "missing pod name";
        }
        try {
            ChunkPaths.relative(metadata);
            return null;
        } catch (InvalidIdentityException error) {
            return error.getMessage();
        }
    }

    private void quarantineOrphanPayloads(Path root) throws IOException {
        Path chunks = root.resolve("chunks");
        if (Files.isDirectory(chunks)) {
            List<Path> payloads;
            try (Stream<Path> files = Files.list(chunks)) {
                payloads = files.filter(path -> path.getFileName().toString().endsWith(".jfr")).toList();
            }
            for (Path payload : payloads) {
                String name = payload.getFileName().toString();
                String chunkId = name.substring(0, name.length() - 4);
                quarantine(root, chunkId, "orphan payload", payload);
            }
        }
        Path data = root.resolve("data");
        if (!Files.isDirectory(data)) {
            return;
        }
        List<Path> buckets;
        try (Stream<Path> children = Files.list(data)) {
            buckets = children.filter(path -> Files.isDirectory(path) && isHexBucket(path.getFileName().toString()))
                    .toList();
        }
        for (Path bucket : buckets) {
            List<Path> payloads;
            try (Stream<Path> files = Files.list(bucket)) {
                payloads = files.filter(Files::isRegularFile).toList();
            }
            for (Path payload : payloads) {
                Path meta = root.resolve("meta").resolve(bucket.getFileName()).resolve(payload.getFileName());
                if (Files.exists(meta)) {
                    continue;
                }
                ChunkFileName.Parsed parsed = ChunkFileName.parse(payload.getFileName().toString());
                String chunkId = parsed == null ? payload.getFileName().toString() : parsed.chunkId();
                quarantine(root, chunkId, "orphan payload", payload);
            }
        }
    }

    private void quarantine(Path root, String chunkId, String reason, Path... files) throws IOException {
        Path dir = root.resolve("data").resolve("quarantine");
        Files.createDirectories(dir);
        String id = safeId(chunkId);
        boolean moved = false;
        for (Path file : files) {
            if (file == null || !Files.isRegularFile(file)) {
                continue;
            }
            String suffix = file.getFileName().toString().endsWith(".jfr") ? ".jfr" : ".json";
            Path dest = dir.resolve(id + suffix);
            moveReplacing(file, dest);
            moved = true;
        }
        if (!moved && (chunkId == null || chunkId.isBlank())) {
            return;
        }
        Files.writeString(dir.resolve(id + ".reason.txt"), reason + System.lineSeparator());
        quarantined++;
        LOG.infof("quarantined %s: %s", id, reason);
    }

    private void cleanup(Path root) throws IOException {
        deleteParts(root.resolve("tmp"));
        deleteTree(root.resolve("stitch-cache"));
        deleteDerivedRecordings(root.resolve("recordings"));
        deleteIfEmpty(root.resolve("chunks"));
        deleteHexChildren(root.resolve("meta"));
        deleteIfEmpty(root.resolve("meta"));
        deleteHexChildren(root.resolve("data"));
    }

    private static void deleteDerivedRecordings(Path recordings) throws IOException {
        if (!Files.exists(recordings)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(recordings, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (name.equals("stitched.jfr") || name.equals("manifest.json")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        for (Path file : files) {
            ChunkStore.deleteQuietly(file);
        }
        List<Path> directories = new ArrayList<>();
        Files.walkFileTree(recordings, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) {
                directories.add(dir);
                return FileVisitResult.CONTINUE;
            }
        });
        for (int i = directories.size() - 1; i >= 0; i--) {
            Path dir = directories.get(i);
            if (dir.equals(recordings)) {
                continue;
            }
            deleteIfEmpty(dir);
        }
    }

    private static void deleteHexChildren(Path parent) throws IOException {
        if (!Files.isDirectory(parent)) {
            return;
        }
        List<Path> children;
        try (Stream<Path> listed = Files.list(parent)) {
            children = listed.filter(path -> isHexBucket(path.getFileName().toString())).toList();
        }
        for (Path child : children) {
            deleteIfEmpty(child);
        }
    }

    private static void deleteIfEmpty(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            if (children.findAny().isPresent()) {
                return;
            }
        }
        Files.deleteIfExists(dir);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                ChunkStore.deleteQuietly(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) {
                ChunkStore.deleteQuietly(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteParts(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> parts = Files.list(dir)) {
            parts.filter(path -> path.getFileName().toString().endsWith(".part")).forEach(ChunkStore::deleteQuietly);
        }
    }

    private static ChunkMetadata readMeta(Path meta) {
        try {
            return MAPPER.readValue(meta.toFile(), ChunkMetadata.class);
        } catch (IOException error) {
            LOG.warnf(error, "unreadable legacy meta %s", meta);
            return null;
        }
    }

    private static Path siblingPayload(Path chunks, Path meta) {
        String name = meta.getFileName().toString();
        String stem = name.endsWith(".meta") ? name.substring(0, name.length() - 5) : name;
        return chunks.resolve(stem + ".jfr");
    }

    private static String chunkIdFrom(Path meta) {
        String name = meta.getFileName().toString();
        if (name.endsWith(".meta")) {
            return name.substring(0, name.length() - 5);
        }
        ChunkFileName.Parsed parsed = ChunkFileName.parse(name);
        return parsed == null ? name : parsed.chunkId();
    }

    private static boolean containsNamed(Path root, String fileName) throws IOException {
        if (!Files.exists(root)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.anyMatch(path -> path.getFileName().toString().equals(fileName));
        }
    }

    private static boolean isHexBucket(String name) {
        if (name.length() != 8) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static String safeId(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            return "unknown";
        }
        return chunkId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(
                tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public record Result(long migrated, long quarantined, long dropped) {}

    @FunctionalInterface
    public interface Checkpoint {
        void reach(String phase) throws IOException;
    }
}

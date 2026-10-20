package io.jafra.analyzer.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.protobuf.ByteString;

import io.jafra.analyzer.index.ChunkIndexRow;
import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkPaths;
import io.jafra.analyzer.storage.ChunkStore;
import io.jafra.ingest.v1.AckStatus;
import io.jafra.ingest.v1.ChunkFrame;
import io.jafra.ingest.v1.CommitChunk;
import io.jafra.ingest.v1.OpenChunk;
import io.jafra.ingest.v1.UploadAck;
import io.jafra.ingest.v1.UploadRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class IngestWritePathTest {
    @Test
    void acceptedChunkLandsAtTheHierarchicalPath(@TempDir Path root) throws Exception {
        byte[] payload = "FLR\0accepted".getBytes(StandardCharsets.UTF_8);
        ChunkStore store = new ChunkStore(root);
        store.recover();
        try {
            IngestRegistry registry = registry(store);
            UploadAck ack = ingest(registry, open(payload.length, "auth-cache"), payload, sha256(payload));
            assertEquals(AckStatus.ACCEPTED, ack.getStatus());
            String chunkId = IngestSession.chunkId(open(payload.length, "auth-cache"));
            ChunkMetadata metadata = storedMetadata(chunkId, payload.length);
            Path payloadPath = ChunkPaths.payload(root, metadata);
            assertEquals(payloadPath, store.payloadPath(chunkId));
            assertTrue(Files.isRegularFile(payloadPath));
            assertTrue(Files.isRegularFile(ChunkPaths.sidecar(root, metadata)));
            assertFalse(Files.exists(root.resolve("data/tmp").resolve(chunkId + ".part")));
            assertEquals(ChunkIndexRow.STATUS_STORED, store.index().get(chunkId).status());
        } finally {
            store.close();
        }
    }

    @Test
    void openOfAnExistingChunkIsDuplicateAndLeavesNoPart(@TempDir Path root) throws Exception {
        byte[] payload = "FLR\0dup-open".getBytes(StandardCharsets.UTF_8);
        ChunkStore store = new ChunkStore(root);
        store.recover();
        try {
            IngestRegistry registry = registry(store);
            assertEquals(AckStatus.ACCEPTED, ingest(registry, open(payload.length, "auth-cache"), payload, sha256(payload)).getStatus());
            IngestRegistry.StreamContext replay = registry.openStream();
            UploadAck ack = registry.handle(replay, UploadRequest.newBuilder().setOpen(open(payload.length, "auth-cache")).build());
            registry.closeStream(replay);
            assertEquals(AckStatus.DUPLICATE, ack.getStatus());
            String chunkId = IngestSession.chunkId(open(payload.length, "auth-cache"));
            assertFalse(Files.exists(root.resolve("data/tmp").resolve(chunkId + ".part")));
        } finally {
            store.close();
        }
    }

    @Test
    void primaryKeyRaceDeletesThePartAndReturnsDuplicate(@TempDir Path root) throws Exception {
        byte[] payload = "FLR\0pk-race".getBytes(StandardCharsets.UTF_8);
        ChunkStore store = new ChunkStore(root);
        store.recover();
        try {
            OpenChunk open = open(payload.length, "auth-cache");
            String chunkId = IngestSession.chunkId(open);
            IngestRegistry registry = registry(store);
            IngestRegistry.StreamContext context = registry.openStream();
            assertNull(registry.handle(context, UploadRequest.newBuilder().setOpen(open).build()));
            assertNull(registry.handle(context, UploadRequest.newBuilder().setFrame(frame(open, chunkId, payload)).build()));
            assertTrue(store.index().insertPending(pending(chunkId, payload.length)));
            UploadAck ack = registry.handle(context, UploadRequest.newBuilder().setCommit(commit(open, chunkId, payload, sha256(payload))).build());
            registry.closeStream(context);
            assertEquals(AckStatus.DUPLICATE, ack.getStatus());
            assertFalse(Files.exists(root.resolve("data/tmp").resolve(chunkId + ".part")));
            assertFalse(Files.exists(ChunkPaths.payload(root, storedMetadata(chunkId, payload.length))));
        } finally {
            store.close();
        }
    }

    @Test
    void checksumMismatchDeletesThePart(@TempDir Path root) throws Exception {
        byte[] payload = "FLR\0bad-sum".getBytes(StandardCharsets.UTF_8);
        ChunkStore store = new ChunkStore(root);
        store.recover();
        try {
            OpenChunk open = open(payload.length, "auth-cache");
            String chunkId = IngestSession.chunkId(open);
            UploadAck ack = ingest(registry(store), open, payload, "0000");
            assertEquals(AckStatus.REJECTED, ack.getStatus());
            assertTrue(ack.getMessage().contains("checksum"));
            assertFalse(Files.exists(root.resolve("data/tmp").resolve(chunkId + ".part")));
            assertFalse(store.contains(chunkId));
        } finally {
            store.close();
        }
    }

    @Test
    void cancelledStreamDeletesThePart(@TempDir Path root) throws Exception {
        byte[] payload = "FLR\0cancel".getBytes(StandardCharsets.UTF_8);
        ChunkStore store = new ChunkStore(root);
        store.recover();
        try {
            OpenChunk open = open(payload.length, "auth-cache");
            String chunkId = IngestSession.chunkId(open);
            IngestRegistry registry = registry(store);
            IngestRegistry.StreamContext context = registry.openStream();
            assertNull(registry.handle(context, UploadRequest.newBuilder().setOpen(open).build()));
            assertNull(registry.handle(context, UploadRequest.newBuilder().setFrame(frame(open, chunkId, payload)).build()));
            assertTrue(Files.exists(root.resolve("data/tmp").resolve(chunkId + ".part")));
            registry.closeStream(context);
            assertFalse(Files.exists(root.resolve("data/tmp").resolve(chunkId + ".part")));
        } finally {
            store.close();
        }
    }

    @Test
    void openRetriesWhileStorageMigrationIsInProgress(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        store.setReady(false);
        try {
            IngestRegistry registry = registry(store);
            IngestRegistry.StreamContext context = registry.openStream();
            UploadAck ack = registry.handle(context, UploadRequest.newBuilder().setOpen(open(4, "auth-cache")).build());
            registry.closeStream(context);
            assertEquals(AckStatus.RETRY, ack.getStatus());
            assertTrue(ack.getMessage().contains("migration"));
        } finally {
            store.close();
        }
    }

    @Test
    void invalidPodNameIsRejected(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        try {
            IngestRegistry registry = registry(store);
            IngestRegistry.StreamContext context = registry.openStream();
            UploadAck ack = registry.handle(context, UploadRequest.newBuilder().setOpen(open(4, "../payments")).build());
            registry.closeStream(context);
            assertEquals(AckStatus.REJECTED, ack.getStatus());
            assertTrue(ack.getMessage().contains("pod"));
        } finally {
            store.close();
        }
    }

    private static IngestRegistry registry(ChunkStore store) {
        return new IngestRegistry(new SimpleMeterRegistry(), store, 8, 4096);
    }

    private static UploadAck ingest(IngestRegistry registry, OpenChunk open, byte[] payload, String checksum) throws Exception {
        IngestRegistry.StreamContext context = registry.openStream();
        String chunkId = IngestSession.chunkId(open);
        assertNull(registry.handle(context, UploadRequest.newBuilder().setOpen(open).build()));
        assertNull(registry.handle(context, UploadRequest.newBuilder().setFrame(frame(open, chunkId, payload)).build()));
        UploadAck ack = registry.handle(context, UploadRequest.newBuilder().setCommit(commit(open, chunkId, payload, checksum)).build());
        registry.closeStream(context);
        return ack;
    }

    private static ChunkFrame frame(OpenChunk open, String chunkId, byte[] payload) {
        return ChunkFrame.newBuilder()
                .setRecordingId(open.getRecordingId())
                .setChunkId(chunkId)
                .setFrameOffset(0)
                .setPayload(ByteString.copyFrom(payload))
                .build();
    }

    private static CommitChunk commit(OpenChunk open, String chunkId, byte[] payload, String checksum) {
        return CommitChunk.newBuilder()
                .setRecordingId(open.getRecordingId())
                .setChunkId(chunkId)
                .setTotalBytes(payload.length)
                .setChecksumAlgorithm("sha256")
                .setChecksum(checksum)
                .build();
    }

    private static OpenChunk open(int length, String podName) {
        return OpenChunk.newBuilder()
                .setProtocolVersion(1)
                .setClusterId("local-demo")
                .setNodeName("worker-1")
                .setNamespace("default")
                .setPodUid("pod")
                .setPodName(podName)
                .setContainerName("auth-cache")
                .setPhysicalFilename("profile-0.jfr")
                .setRecordingId("local-demo/pod/auth-cache/profile-0.jfr")
                .setChunkOffset(0)
                .setChunkLength(length)
                .build();
    }

    private static ChunkMetadata storedMetadata(String chunkId, int length) {
        return new ChunkMetadata(
                chunkId,
                "local-demo/pod/auth-cache/profile-0.jfr",
                "local-demo",
                "default",
                "pod",
                "auth-cache",
                "auth-cache",
                "profile-0.jfr",
                0,
                length,
                "checksum");
    }

    private static ChunkIndexRow pending(String chunkId, int length) {
        ChunkMetadata metadata = storedMetadata(chunkId, length);
        return new ChunkIndexRow(
                chunkId,
                metadata.recordingId(),
                "local-demo",
                "worker-1",
                "default",
                "auth-cache",
                "pod",
                "auth-cache",
                "default",
                "profile-0.jfr",
                0,
                0,
                0L,
                length,
                0,
                0,
                0,
                0,
                "checksum",
                ChunkPaths.relative(metadata),
                ChunkIndexRow.STATUS_PENDING,
                null,
                null);
    }

    private static String sha256(byte[] payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    }
}

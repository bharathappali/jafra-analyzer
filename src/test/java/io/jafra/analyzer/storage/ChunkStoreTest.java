package io.jafra.analyzer.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkStoreTest {
    @Test
    void commitIsDurableAcrossRestartWithoutEagerStitch(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        ChunkMetadata first = metadata("chunk-a", 0, new byte[] {1, 2, 3, 4});
        persist(store, first, new byte[] {1, 2, 3, 4});

        assertTrue(Files.exists(store.payloadPath("chunk-a")));
        assertFalse(Files.exists(root.resolve("stitch-cache")));
        assertTrue(Files.walk(root.resolve("recordings"))
                .noneMatch(path -> path.getFileName().toString().equals("stitched.jfr")));

        ChunkStore restarted = new ChunkStore(root);
        restarted.recover();
        assertTrue(restarted.contains("chunk-a"));
        assertEquals(List.of("chunk-a"), restarted.contiguousChunks(first.recordingId()).stream()
                .map(ChunkMetadata::chunkId)
                .toList());
        Path stitched = root.resolve("on-demand.jfr");
        assertEquals(4, restarted.stitchTo(stitched, List.of(first.recordingId())));
        assertArrayEquals(new byte[] {1, 2, 3, 4}, Files.readAllBytes(stitched));
    }

    @Test
    void payloadWithoutMetaIsDiscardedOnRecover(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        ChunkStore.IncomingWrite write = store.beginWrite("orphan");
        write.write(new byte[] {9, 9});
        write.force();
        write.closeQuietly();
        Files.createDirectories(root.resolve("chunks"));
        Files.move(root.resolve("tmp/orphan.part"), root.resolve("chunks/orphan.jfr"));

        ChunkStore restarted = new ChunkStore(root);
        restarted.recover();
        assertFalse(restarted.contains("orphan"));
        assertFalse(Files.exists(root.resolve("chunks/orphan.jfr")));
        assertFalse(Files.exists(root.resolve("tmp/orphan.part")));
    }

    @Test
    void outOfOrderChunksWaitForTheHole(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        byte[] later = new byte[] {5, 6};
        persist(store, metadata("chunk-b", 4, later), later);
        assertTrue(store.contiguousChunks("local-demo/pod/auth-cache/profile-0.jfr").isEmpty());

        byte[] early = new byte[] {1, 2, 3, 4};
        persist(store, metadata("chunk-a", 0, early), early);
        assertEquals(2, store.contiguousChunks("local-demo/pod/auth-cache/profile-0.jfr").size());
        Path stitched = root.resolve("stitched-on-demand.jfr");
        store.stitchTo(stitched, List.of("local-demo/pod/auth-cache/profile-0.jfr"));
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, Files.readAllBytes(stitched));
    }

    @Test
    void recoverDeletesLegacyDurableStitchedFiles(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        persist(store, metadata("chunk-a", 0, new byte[] {1, 2}), new byte[] {1, 2});

        Path legacyDir = root.resolve("recordings/local-demo/pod/auth-cache/profile-0.jfr");
        Files.createDirectories(legacyDir);
        Files.write(legacyDir.resolve("stitched.jfr"), new byte[] {9, 9, 9});
        Files.writeString(legacyDir.resolve("manifest.json"), "{\"nextOffset\":2}");

        ChunkStore restarted = new ChunkStore(root);
        restarted.recover();
        assertFalse(Files.exists(legacyDir.resolve("stitched.jfr")));
        assertFalse(Files.exists(legacyDir.resolve("manifest.json")));
        assertTrue(restarted.contains("chunk-a"));
    }

    @Test
    void recoverMovesLegacyChunkAndDoesNotMoveItAgain(@TempDir Path root) throws Exception {
        Path chunks = root.resolve("chunks");
        Files.createDirectories(chunks);
        byte[] payload = new byte[68];
        ByteBuffer header = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        header.put(new byte[] {'F', 'L', 'R', 0});
        header.putInt(2);
        header.putLong(68);
        header.putLong(68);
        header.putLong(68);
        header.putLong(1_750_000_000_000_000_000L);
        header.putLong(5_000_000_000L);
        Files.write(chunks.resolve("chunk-a.jfr"), payload);
        Files.writeString(chunks.resolve("chunk-a.meta"), """
                {"chunkId":"chunk-a","recordingId":"local-demo/pod/auth-cache/profile-0.jfr","clusterId":"local-demo","namespace":"default","podUid":"pod","podName":"auth-cache","containerName":"auth-cache","physicalFilename":"profile-0.jfr","chunkOffset":0,"chunkLength":68,"checksum":"checksum","chunkStartTimeNs":0,"chunkDurationNs":0}
                """);

        ChunkStore store = new ChunkStore(root);
        store.recover();
        assertTrue(store.contains("chunk-a"));
        assertFalse(Files.exists(chunks.resolve("chunk-a.jfr")));
        assertFalse(Files.exists(chunks.resolve("chunk-a.meta")));
        ChunkStore.StoredChunk stored = store.storedChunks().getFirst();
        assertEquals("chunk-a", stored.parsed().chunkId());
        assertEquals(1_750_000_000L, stored.parsed().startSeconds());
        Path firstPayload = stored.payload();

        ChunkStore restarted = new ChunkStore(root);
        restarted.recover();
        assertEquals(firstPayload, restarted.storedChunks().getFirst().payload());
        assertTrue(restarted.contains("chunk-a"));
    }

    private static void persist(ChunkStore store, ChunkMetadata metadata, byte[] payload) throws Exception {
        ChunkStore.IncomingWrite write = store.beginWrite(metadata.chunkId());
        write.write(payload);
        store.commit(write, metadata);
    }

    private static ChunkMetadata metadata(String chunkId, long offset, byte[] payload) {
        return new ChunkMetadata(
                chunkId,
                "local-demo/pod/auth-cache/profile-0.jfr",
                "local-demo",
                "default",
                "pod",
                "auth-cache",
                "auth-cache",
                "profile-0.jfr",
                offset,
                payload.length,
                "checksum");
    }
}

package io.jafra.analyzer.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkStoreTest {
    @Test
    void commitIsDurableAcrossRestart(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        ChunkMetadata first = metadata("chunk-a", 0, new byte[] {1, 2, 3, 4});
        persist(store, first, new byte[] {1, 2, 3, 4});

        ChunkStore restarted = new ChunkStore(root);
        restarted.recover();
        assertTrue(restarted.contains("chunk-a"));
        assertEquals(4, Files.size(restarted.stitchedFile(first.recordingId())));
        assertArrayEquals(new byte[] {1, 2, 3, 4}, Files.readAllBytes(restarted.stitchedFile(first.recordingId())));
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
        assertEquals(0, Files.size(store.stitchedFile("local-demo/pod/auth-cache/profile-0.jfr")));

        byte[] early = new byte[] {1, 2, 3, 4};
        persist(store, metadata("chunk-a", 0, early), early);
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6},
                Files.readAllBytes(store.stitchedFile("local-demo/pod/auth-cache/profile-0.jfr")));
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
                "profile-0.jfr",
                offset,
                payload.length,
                "checksum");
    }
}

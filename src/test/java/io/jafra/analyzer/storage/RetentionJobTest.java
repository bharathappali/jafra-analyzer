package io.jafra.analyzer.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jafra.analyzer.index.ChunkIndexRow;

class RetentionJobTest {
    @Test
    void sweepDeletesOnlyTheExpiredChunk(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        try {
            ChunkMetadata expired = metadata("expired", 1_000, 50);
            ChunkMetadata fresh = metadata("fresh", TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis()), Duration.ofHours(2).toNanos());
            write(store, expired);
            write(store, fresh);
            Path quarantine = root.resolve("data/quarantine/keep.jfr");
            Files.write(quarantine, new byte[] {1});

            new RetentionJob(store, Optional.of(Duration.ofHours(1))).purge(Duration.ofHours(1));

            assertNull(store.index().get("expired"));
            assertFalse(Files.exists(ChunkPaths.payload(root, expired)));
            assertFalse(Files.exists(ChunkPaths.sidecar(root, expired)));
            assertNotNull(store.index().get("fresh"));
            assertEqualsStored(store, fresh);
            assertTrue(Files.isRegularFile(quarantine));
            assertTrue(Files.isDirectory(root.resolve("data/recordings")));
        } finally {
            store.close();
        }
    }

    private static void assertEqualsStored(ChunkStore store, ChunkMetadata metadata) throws Exception {
        assertTrue(Files.isRegularFile(ChunkPaths.payload(rootOf(store), metadata)));
        assertTrue(ChunkIndexRow.STATUS_STORED.equals(store.index().get(metadata.chunkId()).status()));
    }

    private static Path rootOf(ChunkStore store) {
        return store.root();
    }

    private static void write(ChunkStore store, ChunkMetadata metadata) throws Exception {
        ChunkStore.IncomingWrite incoming = store.beginWrite(metadata.chunkId());
        incoming.write(new byte[] {1, 2, 3});
        store.commit(incoming, metadata);
    }

    private static ChunkMetadata metadata(String chunkId, long startNs, long durationNs) {
        return new ChunkMetadata(
                chunkId,
                "rec",
                "default",
                "ns",
                "uid",
                "pod",
                "app",
                "profile.jfr",
                0,
                3,
                "checksum",
                startNs,
                durationNs);
    }
}

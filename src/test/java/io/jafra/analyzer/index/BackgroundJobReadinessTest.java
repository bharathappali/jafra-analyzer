package io.jafra.analyzer.index;

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

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkPaths;
import io.jafra.analyzer.storage.ChunkStore;
import io.jafra.analyzer.storage.RetentionJob;

class BackgroundJobReadinessTest {
    @Test
    void jobsDoNothingUntilTheStoreIsReady(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        EventRangeDecoder decoder = new EventRangeDecoder(store.index(), root, 1);
        DecodeRescanJob rescan = new DecodeRescanJob(store, decoder);
        RetentionJob retention = new RetentionJob(store, Optional.of(Duration.ofHours(1)));
        try {
            ChunkMetadata expired = metadata("expired", 1_000, 50);
            ChunkMetadata fresh = metadata(
                    "fresh",
                    TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis()),
                    Duration.ofHours(2).toNanos());
            write(store, expired, new byte[] {1, 2, 3});
            write(store, fresh, new byte[] {4, 5, 6, 7});
            Path freshSidecar = ChunkPaths.sidecar(root, fresh);
            Files.delete(freshSidecar);

            store.setReady(false);
            retention.purge(Duration.ofHours(1));
            rescan.rescan();
            decoder.awaitIdle(Duration.ofSeconds(2));
            store.repairMissingSidecars();

            assertNotNull(store.index().get("expired"));
            assertTrue(Files.isRegularFile(ChunkPaths.payload(root, expired)));
            assertTrue(Files.isRegularFile(ChunkPaths.sidecar(root, expired)));
            assertNull(store.index().get("fresh").decodedAt());
            assertFalse(Files.exists(freshSidecar));

            store.setReady(true);
            rescan.rescan();
            decoder.awaitIdle(Duration.ofSeconds(10));
            retention.purge(Duration.ofHours(1));
            store.repairMissingSidecars();

            assertNotNull(store.index().get("fresh").decodedAt());
            assertTrue(Files.isRegularFile(freshSidecar));
            assertNull(store.index().get("expired"));
            assertFalse(Files.exists(ChunkPaths.payload(root, expired)));
            assertFalse(Files.exists(ChunkPaths.sidecar(root, expired)));
        } finally {
            decoder.close();
            store.close();
        }
    }

    private static void write(ChunkStore store, ChunkMetadata metadata, byte[] payload) throws Exception {
        ChunkStore.IncomingWrite incoming = store.beginWrite(metadata.chunkId());
        incoming.write(payload);
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
                4,
                "checksum",
                startNs,
                durationNs);
    }
}

package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;

class RelocateLockTest {
    @Test
    void relocateHoldsTheCatalogLockUntilTheMoveFinishes() throws Exception {
        Path root = Files.createTempDirectory("jafra-relocate-lock");
        Path v2 = root.resolve("v2");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean();
        try (IngestCatalog catalog = new IngestCatalog(v2)) {
            ChunkDecodeService service = new ChunkDecodeService(new ChunkStore(root), catalog, v2);
            service.ingestChunk(meta("early", 1_000L), chunk(false));
            catalog.ensureInstance("side-jvm", "ns", "pod", "app", "side", 1L, false);
            InstanceFiles.beforeRelocate = claim -> {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            };
            Thread mover = new Thread(() -> {
                try {
                    service.ingestChunk(meta("named", 2_000L), chunk(true));
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            });
            Thread other = new Thread(() -> {
                try {
                    catalog.complete("side", "side-jvm", "side", 1L, 2L, 1L, 1L, null);
                    completed.set(true);
                    finished.countDown();
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            });
            try {
                mover.start();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                other.start();
                assertFalse(finished.await(300, TimeUnit.MILLISECONDS));
                release.countDown();
                assertTrue(finished.await(5, TimeUnit.SECONDS));
                mover.join(5_000L);
                assertTrue(completed.get());
                assertEquals("done", status(catalog, "side"));
                assertEquals("done", status(catalog, "early"));
                assertEquals(2, catalog.instances().size());
            } finally {
                release.countDown();
                InstanceFiles.beforeRelocate = null;
            }
        }
    }

    private static JfrChunkReader.ReadChunk chunk(boolean jvm) {
        JfrChunkReader.ReadChunk chunk = new JfrChunkReader.ReadChunk();
        if (jvm) {
            chunk.jvm = new JfrChunkReader.JvmFacts("21", "HotSpot", 1L, 7L);
        }
        chunk.gauges.add(new JfrChunkReader.GaugePoint(1_500L, "cpu.jvm_user", 0.2));
        return chunk;
    }

    private static ChunkMetadata meta(String chunkId, long start) {
        return new ChunkMetadata(chunkId, "rec-" + chunkId, "cluster", "ns", "uid", "pod", "app", "rec", 0L, 1L, "sum", start, 1_000L);
    }

    private static String status(IngestCatalog catalog, String chunkId) throws Exception {
        try (var statement = catalog.connection().prepareStatement("SELECT status FROM chunk_ingest WHERE chunk_id = ?")) {
            statement.setString(1, chunkId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }
}

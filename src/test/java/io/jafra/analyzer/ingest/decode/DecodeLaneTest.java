package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;

class DecodeLaneTest {
    @Test
    void oneStuckPodDoesNotBlockAnother() throws Exception {
        String other = "pod-other";
        int lanes = 8;
        int stuckLane = DecodeLanes.of("ns", "stuck", "app", "rec-stuck", lanes);
        for (int i = 0; i < 40 && DecodeLanes.of("ns", other, "app", "rec-other", lanes) == stuckLane; i++) {
            other = "pod-other-" + i;
        }
        assertTrue(DecodeLanes.of("ns", other, "app", "rec-other", lanes) != stuckLane);
        Path root = Files.createTempDirectory("jafra-decode-lanes");
        Path v2 = root.resolve("v2");
        try (IngestCatalog catalog = new IngestCatalog(v2)) {
            ChunkDecodeService service = new ChunkDecodeService(new ChunkStore(root), catalog, v2);
            service.enqueue(chunk("chunk-stuck", "rec-stuck", "stuck", 1L));
            service.enqueue(chunk("chunk-other", "rec-other", other, 1L));
            IngestCatalog.Claimed first = catalog.claim(stuckLane, lanes, Instant.now());
            IngestCatalog.Claimed second = catalog.claim(DecodeLanes.of("ns", other, "app", "rec-other", lanes), lanes, Instant.now());
            assertNotNull(first);
            assertNotNull(second);
            assertEquals("chunk-stuck", first.chunkId());
            assertEquals("chunk-other", second.chunkId());

            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            DecodeGate.before = metadata -> {
                if ("stuck".equals(metadata.podName())) {
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            try {
                AtomicReference<Exception> failure = new AtomicReference<>();
                String freePod = other;
                Thread blocked = new Thread(() -> ingest(service, "chunk-stuck", "rec-stuck", "stuck", failure));
                Thread free = new Thread(() -> ingest(service, "chunk-free", "rec-free", freePod, failure));
                blocked.start();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                free.start();
                free.join(5_000L);
                assertEquals("done", status(catalog, "chunk-free"));
                release.countDown();
                blocked.join(5_000L);
                assertEquals("done", status(catalog, "chunk-stuck"));
                if (failure.get() != null) {
                    throw failure.get();
                }
            } finally {
                DecodeGate.before = null;
                release.countDown();
            }
        }
    }

    private static void ingest(ChunkDecodeService service, String chunkId, String recording, String pod, AtomicReference<Exception> failure) {
        try {
            JfrChunkReader.ReadChunk chunk = new JfrChunkReader.ReadChunk();
            chunk.jvm = new JfrChunkReader.JvmFacts("21", "HotSpot", 1_000L, 1L);
            chunk.gauges.add(new JfrChunkReader.GaugePoint(2_000L, "cpu.jvm_user", 0.2));
            service.ingestChunk(chunk(chunkId, recording, pod, 1_000L), chunk);
        } catch (Exception error) {
            failure.set(error);
        }
    }

    private static ChunkMetadata chunk(String chunkId, String recording, String pod, long start) {
        return new ChunkMetadata(chunkId, recording, "cluster", "ns", "uid-" + pod, pod, "app", recording, 0L, 1L, "sum", start, 1_000L);
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

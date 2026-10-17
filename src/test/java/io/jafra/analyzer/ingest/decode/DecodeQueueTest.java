package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;

class DecodeQueueTest {
    @Test
    void aThrownDecodeIsRetriedThenQuarantinedAndACrashResetsDecoding() throws Exception {
        Path root = Files.createTempDirectory("jafra-decode-queue");
        Path v2 = root.resolve("v2");
        byte[] payload = DecodeQueueTest.class.getResourceAsStream(
                "/jfr-fixtures/chunks/00044f5ca5f798ff6778a9dac78f29ee3903ff5bf74d33c1b2dd6194234c0709.jfr").readAllBytes();
        try (IngestCatalog catalog = new IngestCatalog(v2)) {
            ChunkStore store = new ChunkStore(root);
            store.recover();
            ChunkDecodeService service = new ChunkDecodeService(store, catalog, v2);
            ChunkMetadata metadata = metadata("chunk-ok", "rec-a", "pod-a", 1_000L);
            commit(store, metadata, payload);
            service.enqueue(metadata);
            AtomicInteger throwsLeft = new AtomicInteger(1);
            DecodeGate.before = chunk -> {
                if (throwsLeft.getAndDecrement() > 0) {
                    throw new IllegalStateException("reader blew up");
                }
            };
            try {
                assertTrue(service.decodeNextAny(Instant.now()));
                assertEquals("failed", status(catalog, "chunk-ok"));
                assertTrue(error(catalog, "chunk-ok").contains("reader blew up"));
                assertNull(catalog.claimAny(Instant.now()));
                assertTrue(service.decodeNextAny(Instant.now().plusSeconds(5)));
                assertEquals("done", status(catalog, "chunk-ok"));

                ChunkMetadata poisoned = metadata("chunk-bad", "rec-b", "pod-a", 2_000L);
                service.enqueue(poisoned);
                for (int attempt = 0; attempt < 4; attempt++) {
                    catalog.fail("chunk-bad", "still bad");
                    assertEquals("failed", status(catalog, "chunk-bad"));
                }
                catalog.fail("chunk-bad", "still bad");
                assertEquals("quarantined", status(catalog, "chunk-bad"));
                assertNull(catalog.claimAny(Instant.now().plusSeconds(60)));

                ChunkMetadata crashed = metadata("chunk-crash", "rec-c", "pod-a", 3_000L);
                service.enqueue(crashed);
                assertNotNull(catalog.claimAny(Instant.now()));
                assertEquals("decoding", status(catalog, "chunk-crash"));
                service.recover();
                assertEquals("pending", status(catalog, "chunk-crash"));
            } finally {
                DecodeGate.before = null;
            }
        }
    }

    private static ChunkMetadata metadata(String chunkId, String recording, String pod, long start) {
        return new ChunkMetadata(chunkId, recording, "cluster", "ns", "uid-" + pod, pod, "app", recording, 0L, 1L, "sum", start, 1_000L);
    }

    @Test
    void requeueReturnsOneChunkOrEveryQuarantinedChunk() throws Exception {
        Path v2 = Files.createTempDirectory("jafra-requeue").resolve("v2");
        try (IngestCatalog catalog = new IngestCatalog(v2)) {
            catalog.quarantine("chunk-a", "give up");
            catalog.quarantine("chunk-b", "give up");
            assertEquals(1, catalog.requeue("chunk-a"));
            assertEquals("pending", status(catalog, "chunk-a"));
            assertEquals("quarantined", status(catalog, "chunk-b"));
            assertEquals(1, catalog.requeueAll());
            assertEquals("pending", status(catalog, "chunk-b"));
        }
    }

    private static void commit(ChunkStore store, ChunkMetadata metadata, byte[] payload) throws Exception {
        ChunkStore.IncomingWrite incoming = store.beginWrite(metadata.chunkId());
        incoming.write(payload);
        store.commit(incoming, metadata);
    }

    private static String status(IngestCatalog catalog, String chunkId) throws Exception {
        try (var statement = catalog.connection().prepareStatement("SELECT status FROM chunk_ingest WHERE chunk_id = ?")) {
            statement.setString(1, chunkId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static String error(IngestCatalog catalog, String chunkId) throws Exception {
        try (var statement = catalog.connection().prepareStatement("SELECT error FROM chunk_ingest WHERE chunk_id = ?")) {
            statement.setString(1, chunkId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }
}

package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkMetadata;

class SqlClaimTest {
    @Test
    void claimReturnsTheEarliestHeadAndAQuarantineDoesNotBlockThePod() throws Exception {
        int lanes = 8;
        String other = "pod-b";
        int laneA = DecodeLanes.of("ns", "pod-a", "app", lanes);
        for (int i = 0; i < 40 && DecodeLanes.of("ns", other, "app", lanes) == laneA; i++) {
            other = "pod-b-" + i;
        }
        Path v2 = Files.createTempDirectory("jafra-sql-claim").resolve("v2");
        try (IngestCatalog catalog = new IngestCatalog(v2)) {
            catalog.setLanes(lanes);
            catalog.enqueue(chunk("a-late", "pod-a", 30));
            catalog.enqueue(chunk("a-early", "pod-a", 10));
            catalog.enqueue(chunk("a-mid", "pod-a", 20));
            catalog.enqueue(chunk("b-mid", other, 15));
            catalog.enqueue(chunk("b-early", other, 5));
            catalog.enqueue(chunk("b-late", other, 25));

            IngestCatalog.Claimed first = catalog.claim(laneA, lanes, Instant.now());
            assertNotNull(first);
            assertEquals("a-early", first.chunkId());
            assertNull(catalog.claim(laneA, lanes, Instant.now()));

            catalog.quarantine("a-early", "give up");
            IngestCatalog.Claimed next = catalog.claim(laneA, lanes, Instant.now());
            assertNotNull(next);
            assertEquals("a-mid", next.chunkId());
            assertEquals("b-early", catalog.claim(DecodeLanes.of("ns", other, "app", lanes), lanes, Instant.now()).chunkId());
        }
    }

    @Test
    void setLanesClearsLanesOutsideTheNewWidth() throws Exception {
        Path v2 = Files.createTempDirectory("jafra-set-lanes").resolve("v2");
        try (IngestCatalog catalog = new IngestCatalog(v2)) {
            catalog.setLanes(8);
            catalog.enqueue(chunk("wide", "pod-a", 1));
            catalog.setLanes(2);
            try (var statement = catalog.connection().prepareStatement("SELECT lane FROM chunk_ingest WHERE chunk_id = 'wide'")) {
                try (var rows = statement.executeQuery()) {
                    assertEquals(true, rows.next());
                    assertTrue(rows.getInt(1) < 2);
                }
            }
        }
    }

    private static ChunkMetadata chunk(String chunkId, String pod, long start) {
        return new ChunkMetadata(chunkId, "rec-" + chunkId, "cluster", "ns", "uid-" + pod, pod, "app", "rec", 0L, 1L, "sum", start, 1L);
    }
}

package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;

class RollupFollowupTest {
    @Test
    void aChunkIsDoneWhileRollupsArePaused() throws Exception {
        Path root = Files.createTempDirectory("jafra-rollup-followup");
        Path v2 = root.resolve("v2");
        RollupJobs jobs = new RollupJobs();
        jobs.setPaused(true);
        try (IngestCatalog catalog = new IngestCatalog(v2)) {
            ChunkDecodeService service = new ChunkDecodeService(new ChunkStore(root), catalog, v2, jobs);
            long start = 1_700_000_000_000_000_000L;
            JfrChunkReader.ReadChunk chunk = new JfrChunkReader.ReadChunk();
            chunk.jvm = new JfrChunkReader.JvmFacts("21", "HotSpot", start / 1_000_000L, 1L);
            chunk.gauges.add(new JfrChunkReader.GaugePoint(start + 1_000_000_000L, "cpu.jvm_user", 0.5));
            service.ingestChunk(new ChunkMetadata("chunk-roll", "rec", "cluster", "ns", "uid", "pod", "app", "rec", 0L, 1L, "sum", start,
                    5_000_000_000L), chunk);
            try (var statement = catalog.connection().createStatement();
                    var rows = statement.executeQuery("SELECT status FROM chunk_ingest WHERE chunk_id = 'chunk-roll'")) {
                assertTrue(rows.next());
                assertEquals("done", rows.getString(1));
            }
            assertFalse(Files.isDirectory(v2.resolve("rollup_1m")));
            assertEquals(1, catalog.pendingRollups().size());
            jobs.setPaused(false);
            assertTrue(jobs.drain(catalog, v2) > 0);
            assertTrue(catalog.pendingRollups().isEmpty());
            assertTrue(Files.isDirectory(v2.resolve("rollup_1m")));
        }
    }

    @Test
    void aFailedRefreshStaysQueuedAndTheNextJobRuns() throws Exception {
        Path root = Files.createTempDirectory("jafra-rollup-continue");
        Path v2 = root.resolve("v2");
        try (IngestCatalog catalog = new IngestCatalog(v2)) {
            ChunkDecodeService service = new ChunkDecodeService(new ChunkStore(root), catalog, v2);
            ingest(service, "pod-a", "chunk-a");
            ingest(service, "pod-b", "chunk-b");
            RollupJobs.beforeRefresh = job -> {
                if ("pod-a".equals(job.pod())) {
                    throw new IllegalStateException("rollup blew up");
                }
            };
            try {
                service.rollups().drain(catalog, v2);
            } finally {
                RollupJobs.beforeRefresh = null;
            }
            assertEquals(1, catalog.pendingRollups().size());
            assertEquals("pod-a", catalog.pendingRollups().get(0).pod());
            assertTrue(Files.isDirectory(v2.resolve("rollup_1m")));
        }
    }

    @Test
    void adjacentMinutesBecomeOneRangeAndAGapStaysSplit() {
        long minute = RollupJobs.MINUTE_NS;
        IngestCatalog.RollupJob first = new IngestCatalog.RollupJob("ns", "pod", "app", "jvm", 0, minute);
        IngestCatalog.RollupJob second = new IngestCatalog.RollupJob("ns", "pod", "app", "jvm", minute, minute * 2);
        IngestCatalog.RollupJob later = new IngestCatalog.RollupJob("ns", "pod", "app", "jvm", minute * 3, minute * 4);
        assertEquals(2, RollupJobs.merge(List.of(later, first, second)).size());
    }

    private static void ingest(ChunkDecodeService service, String pod, String chunkId) throws Exception {
        long start = 1_700_000_000_000_000_000L;
        JfrChunkReader.ReadChunk chunk = new JfrChunkReader.ReadChunk();
        chunk.jvm = new JfrChunkReader.JvmFacts("21", "HotSpot", start / 1_000_000L, 1L);
        chunk.gauges.add(new JfrChunkReader.GaugePoint(start + 1_000_000_000L, "cpu.jvm_user", 0.5));
        service.ingestChunk(new ChunkMetadata(chunkId, "rec", "cluster", "ns", "uid-" + pod, pod, "app", "rec", 0L, 1L, "sum", start,
                5_000_000_000L), chunk);
    }
}

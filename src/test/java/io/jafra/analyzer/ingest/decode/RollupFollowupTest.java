package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

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
            jobs.setPaused(false);
            assertTrue(service.rollups().drain(catalog.connection(), v2) > 0);
            assertTrue(Files.isDirectory(v2.resolve("rollup_1m")));
        }
    }
}

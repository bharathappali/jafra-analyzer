package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;

/** Reproduces the production symptom: many streamed chunks across file rotations, JVMInformation only in the first chunk. */
class MultiChunkIngestTest {
    private static final long MS = 1_000_000L;
    private static final long SEC = 1_000_000_000L;
    private static final long T0 = 1_792_000_000_000L * MS; // epoch ns
    private static final long START_MS = T0 / MS;

    @Test
    void streamedChunksAcrossRotationsAllLandWithGrowingTimeRange() throws Exception {
        Path root = Files.createTempDirectory("jafra-multichunk");
        Path v2 = root.resolve("v2");
        try (IngestCatalog catalog = new IngestCatalog(v2)) {
            ChunkDecodeService service = new ChunkDecodeService(new ChunkStore(root), catalog, v2);
            long dur = 5 * SEC;
            int chunkNum = 0;
            long t = T0;
            // 3 recordings (file rotations), 4 chunks each. JVMInformation only in the very first chunk of file 0.
            for (int file = 0; file < 3; file++) {
                String recording = "cluster/pod-uid/app/profile-" + file + ".jfr";
                for (int off = 0; off < 4; off++, chunkNum++, t += dur) {
                    boolean first = (file == 0 && off == 0);
                    JfrChunkReader.ReadChunk chunk = new JfrChunkReader.ReadChunk();
                    if (first) {
                        chunk.jvm = new JfrChunkReader.JvmFacts("21.0.12", "HotSpot", START_MS, 1L);
                    }
                    chunk.gauges.add(new JfrChunkReader.GaugePoint(t + SEC, "cpu.jvm_user", 0.5));
                    ChunkMetadata meta = new ChunkMetadata(
                            "chunk-" + chunkNum, recording, "cluster", "ns", "pod-uid", "app", "app",
                            recording, off, 1L, "sum", t, dur);
                    service.ingestChunk(meta, chunk);
                }
            }
            // All 12 chunks must be ingested ok.
            assertEquals(12, okChunks(catalog), "chunks marked ok in chunk_ingest");
            // Exactly one instance (the real one).
            assertEquals(1, catalog.instances().size(), "instance count");
            // jvm_instance parquet time range must span the whole run, not collapse to the first chunk.
            long[] range = instanceTimeRange(v2);
            assertTrue(range[1] - range[0] >= 11 * dur,
                    "jvm_instance time range should span ~" + (11 * dur) + " ns but was " + (range[1] - range[0]) + " (from=" + range[0] + " to=" + range[1] + ")");
            // last_t_ns of the instance must reach the final chunk end.
            long lastT = catalog.instances().get(0).lastTNs();
            assertTrue(lastT >= T0 + 11 * dur, "last_t_ns should reach the final chunk, was " + lastT);
        }
    }

    private static long okChunks(IngestCatalog catalog) throws Exception {
        try (Statement st = catalog.connection().createStatement();
                ResultSet r = st.executeQuery("SELECT count(*) FROM chunk_ingest WHERE status='ok'")) {
            r.next();
            return r.getLong(1);
        }
    }

    private static long[] instanceTimeRange(Path v2) throws Exception {
        IngestCatalog.ensureDriver();
        String glob = v2.resolve("jvm_instance").toAbsolutePath().toString().replace("'", "''") + "/**/*.parquet";
        try (var c = DriverManager.getConnection("jdbc:duckdb:");
                Statement st = c.createStatement();
                ResultSet r = st.executeQuery("SELECT min(t), max(t) FROM read_parquet('" + glob + "', union_by_name=true)")) {
            r.next();
            return new long[]{r.getLong(1), r.getLong(2)};
        }
    }

    @Test
    void longFirstChunkDoesNotOrphanLaterSubChunks() throws Exception {
        Path root = Files.createTempDirectory("jafra-multichunk3");
        Path v2 = root.resolve("v2");
        try (IngestCatalog catalog = new IngestCatalog(v2)) {
            ChunkDecodeService service = new ChunkDecodeService(new ChunkStore(root), catalog, v2);
            String recording = "cluster/pod-uid/app/profile-0.jfr";
            // Realistic agent shape (matches golden profile-607): first chunk declares the whole minute
            // (dur=60s) and carries JVMInformation; later sub-chunks are 5s windows that START INSIDE that span.
            JfrChunkReader.ReadChunk first = new JfrChunkReader.ReadChunk();
            first.jvm = new JfrChunkReader.JvmFacts("21.0.12", "HotSpot", START_MS, 1L);
            first.gauges.add(new JfrChunkReader.GaugePoint(T0 + SEC, "cpu.jvm_user", 0.5));
            service.ingestChunk(new ChunkMetadata("chunk-0", recording, "cluster", "ns", "pod-uid", "app", "app",
                    recording, 0, 216111, "sum", T0, 60 * SEC), first);
            // 5 sub-chunks, no JVMInformation, 5s apart, all within the first chunk's [T0, T0+60s] span.
            long off = 216111;
            for (int i = 1; i <= 5; i++) {
                long tStart = T0 + i * 5 * SEC;
                JfrChunkReader.ReadChunk c = new JfrChunkReader.ReadChunk();
                c.gauges.add(new JfrChunkReader.GaugePoint(tStart + SEC, "cpu.jvm_user", 0.6));
                service.ingestChunk(new ChunkMetadata("chunk-" + i, recording, "cluster", "ns", "pod-uid", "app", "app",
                        recording, off, 30000, "sum", tStart, 5 * SEC), c);
                off += 30000;
            }
            // There must be exactly ONE instance, and its window must span the whole minute.
            assertEquals(1, catalog.instances().size(),
                    "sub-chunks should join the real instance, not spawn provisional ones; instances=" + catalog.instances());
            long[] range = instanceTimeRange(v2);
            assertTrue(range[1] - range[0] >= 25 * SEC,
                    "instance window should span the sub-chunks but was span=" + (range[1] - range[0]));
        }
    }
}

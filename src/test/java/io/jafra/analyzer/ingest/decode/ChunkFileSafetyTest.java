package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.retention.RetentionLog;
import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;

class ChunkFileSafetyTest {
    @Test
    void aTombstoneStopsADecodeFromCreatingFiles() throws Exception {
        Path root = Files.createTempDirectory("jafra-tombstone-decode");
        Path v2 = root.resolve("v2");
        try (IngestCatalog catalog = new IngestCatalog(v2)) {
            ChunkDecodeService service = new ChunkDecodeService(new ChunkStore(root), catalog, v2);
            service.ingestChunk(meta("first", 1_000L), sample(false));
            String instance = catalog.instances().get(0).id();
            RetentionLog.upsert(v2, new RetentionLog.Entry("ns", "pod", "app", instance, "tombstoned", 1L, 1L, 0L, 0L, 0L));
            service.ingestChunk(meta("second", 2_000L), sample(false));
            assertEquals("quarantined", status(catalog, "second"));
            assertFalse(exists(v2, "second.parquet"));
            assertTrue(exists(v2, "first.parquet"));
        }
    }

    @Test
    void aRetryDropsTheCopyWrittenUnderThePreviousInstance() throws Exception {
        Path root = Files.createTempDirectory("jafra-chunk-dedupe");
        Path v2 = root.resolve("v2");
        try (IngestCatalog catalog = new IngestCatalog(v2)) {
            ChunkDecodeService service = new ChunkDecodeService(new ChunkStore(root), catalog, v2);
            JfrChunkReader.ReadChunk chunk = sample(true);
            ChunkMetadata metadata = meta("chunk-retry", 1_000L);
            Path stale = ParquetTables.partitionFile(v2, "gauge", "ns", "pod", "app", "instance-a", 1_000L, "chunk-retry");
            ParquetTables.write(catalog.connection(), stale, "gauge",
                    List.of("namespace", "pod", "container", "jvm_instance_id", "t", "metric", "value"),
                    List.of(List.of("ns", "pod", "app", "instance-a", 1_000L, "cpu.jvm_user", 0.1)));
            service.ingestChunk(metadata, chunk);
            assertFalse(Files.exists(stale));
            assertEquals(1, count(v2, "gauge", "chunk-retry.parquet"));
            assertEquals(1, count(v2, "jvm_instance", "chunk-retry.parquet"));
        }
    }

    private static JfrChunkReader.ReadChunk sample(boolean jvm) {
        JfrChunkReader.ReadChunk chunk = new JfrChunkReader.ReadChunk();
        if (jvm) {
            chunk.jvm = new JfrChunkReader.JvmFacts("21", "HotSpot", 1L, 3L);
        }
        chunk.gauges.add(new JfrChunkReader.GaugePoint(1_000L, "cpu.jvm_user", 0.4));
        return chunk;
    }

    private static ChunkMetadata meta(String chunkId, long start) {
        return new ChunkMetadata(chunkId, "rec", "cluster", "ns", "uid", "pod", "app", "rec", 0L, 1L, "sum", start, 1_000L);
    }

    private static String status(IngestCatalog catalog, String chunkId) throws Exception {
        try (var statement = catalog.connection().prepareStatement("SELECT status FROM chunk_ingest WHERE chunk_id = ?")) {
            statement.setString(1, chunkId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static boolean exists(Path v2, String name) throws Exception {
        return count(v2, "jvm_instance", name) > 0;
    }

    private static long count(Path v2, String table, String name) throws Exception {
        Path dir = v2.resolve(table);
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (var files = Files.walk(dir)) {
            return files.filter(path -> path.getFileName().toString().equals(name)).count();
        }
    }
}

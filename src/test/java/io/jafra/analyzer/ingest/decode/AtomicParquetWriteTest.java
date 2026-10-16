package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;

class AtomicParquetWriteTest {
    @Test
    void aCrashAfterTheTempFileLeavesTheChunkUndoneAndARetryOverwritesIt() throws Exception {
        Path root = Files.createTempDirectory("jafra-atomic-parquet");
        Path v2 = root.resolve("v2");
        AtomicBoolean fail = new AtomicBoolean(true);
        try (IngestCatalog catalog = new IngestCatalog(v2)) {
            ChunkDecodeService service = new ChunkDecodeService(new ChunkStore(root), catalog, v2);
            ChunkMetadata metadata = new ChunkMetadata("chunk-atomic", "rec", "cluster", "ns", "uid", "pod", "app", "rec", 0L, 1L, "sum",
                    1_000L, 1_000L);
            service.enqueue(metadata);
            ParquetTables.beforeRename = temp -> {
                if (fail.get()) {
                    throw new IllegalStateException("stop after temp");
                }
            };
            try {
                try {
                    ingest(service, metadata);
                } catch (IllegalStateException expected) {
                    assertTrue(expected.getMessage().contains("stop after temp"));
                }
                assertEquals("pending", status(catalog));
                assertFalse(published(v2, "jvm_instance"));
                assertTrue(tempExists(v2));
                fail.set(false);
                ingest(service, metadata);
                assertEquals("done", status(catalog));
                assertFalse(tempExists(v2));
                assertEquals(1, filesNamed(v2, "jvm_instance", "chunk-atomic.parquet"));
                assertEquals(1, filesNamed(v2, "gauge", "chunk-atomic.parquet"));
            } finally {
                ParquetTables.beforeRename = null;
            }
        }
    }

    private static void ingest(ChunkDecodeService service, ChunkMetadata metadata) throws Exception {
        JfrChunkReader.ReadChunk chunk = new JfrChunkReader.ReadChunk();
        chunk.jvm = new JfrChunkReader.JvmFacts("21", "HotSpot", 1L, 1L);
        chunk.gauges.add(new JfrChunkReader.GaugePoint(1_000L, "cpu.jvm_user", 0.4));
        service.ingestChunk(metadata, chunk);
    }

    private static String status(IngestCatalog catalog) throws Exception {
        try (var statement = catalog.connection().createStatement();
                var rows = statement.executeQuery("SELECT status FROM chunk_ingest WHERE chunk_id = 'chunk-atomic'")) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static boolean tempExists(Path v2) throws Exception {
        return filesNamed(v2, "jvm_instance", "chunk-atomic.parquet.tmp") > 0;
    }

    private static boolean published(Path v2, String table) throws Exception {
        return filesNamed(v2, table, "chunk-atomic.parquet") > 0;
    }

    private static long filesNamed(Path v2, String table, String name) throws Exception {
        Path dir = v2.resolve(table);
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(path -> path.getFileName().toString().equals(name)).count();
        }
    }
}

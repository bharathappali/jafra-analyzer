package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkStore;
import io.jafra.analyzer.test.ChunkFixtureLoader;
import jdk.jfr.consumer.RecordingFile;

class ChunkDecodeGoldenTest {
    @Test
    void goldenChunksDecodeOnceAndGcRowsMatchTheRecording() throws Exception {
        Path root = Files.createTempDirectory("jafra-decode");
        ChunkStore store = new ChunkStore(root);
        store.recover();
        ChunkFixtureLoader.loadAll(store);
        Path v2 = root.resolve("v2");
        try (IngestCatalog catalog = new IngestCatalog(v2)) {
            ChunkDecodeService service = new ChunkDecodeService(store, catalog, v2);
            service.backfill();
            int decoded = catalog.okCount();
            assertEquals(store.durableChunkCount(), decoded);
            service.backfill();
            assertEquals(decoded, catalog.okCount());

            long gcEvents = 0L;
            for (ChunkStore.StoredChunk stored : store.storedChunks()) {
                try (RecordingFile file = new RecordingFile(stored.payload())) {
                    while (file.hasMoreEvents()) {
                        if ("jdk.GarbageCollection".equals(file.readEvent().getEventType().getName())) {
                            gcEvents++;
                        }
                    }
                }
            }
            long gcRows = countParquet(v2.resolve("gc"));
            assertEquals(gcEvents, gcRows);
            assertTrue(gcRows > 0L);
            assertTrue(countParquet(v2.resolve("jvm_instance")) > 0L);

            long sampleEvents = 0L;
            long durationEvents = 0L;
            for (ChunkStore.StoredChunk stored : store.storedChunks()) {
                try (RecordingFile file = new RecordingFile(stored.payload())) {
                    while (file.hasMoreEvents()) {
                        String type = file.readEvent().getEventType().getName();
                        if (EventRows.isSample(type)) {
                            sampleEvents++;
                        }
                        if (EventRows.isDuration(type)) {
                            durationEvents++;
                        }
                    }
                }
            }
            assertEquals(sampleEvents, countParquet(v2.resolve("sample")));
            assertEquals(durationEvents, countParquet(v2.resolve("duration_event")));
            assertTrue(countParquet(v2.resolve("stack")) > 0L);
            assertTrue(countParquet(v2.resolve("frame")) > 0L);
            assertTrue(countWhere(v2.resolve("marker"), "kind = 'gc_pause'") > 0L);
        }
    }

    private static long countParquet(Path table) throws Exception {
        String glob = table.toAbsolutePath().toString().replace("'", "''") + "/**/*.parquet";
        IngestCatalog.ensureDriver();
        try (var connection = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM read_parquet('" + glob + "', hive_partitioning=false)")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static long countWhere(Path table, String where) throws Exception {
        String glob = table.toAbsolutePath().toString().replace("'", "''") + "/**/*.parquet";
        IngestCatalog.ensureDriver();
        try (var connection = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM read_parquet('" + glob + "', hive_partitioning=false) WHERE " + where)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}

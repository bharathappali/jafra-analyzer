package io.jafra.analyzer.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.api.v2.V2ReadService;
import io.jafra.analyzer.api.v2.V2Window;
import io.jafra.analyzer.ingest.decode.IngestCatalog;
import io.jafra.analyzer.ingest.decode.ParquetTables;
import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;

class RetentionEngineTest {
    @Test
    void anIdleInstanceLosesDetailAndKeepsTheSummary() throws Exception {
        Path root = Files.createTempDirectory("jafra-retention");
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        long old = now.minus(Duration.ofHours(49)).getEpochSecond() * 1_000_000_000L;
        try (IngestCatalog catalog = new IngestCatalog(root.resolve("v2"))) {
            ChunkStore store = store(root);
            seed(store, catalog, root.resolve("v2"), "idle", old);
            seed(store, catalog, root.resolve("v2"), "live", now.minus(Duration.ofSeconds(30)).getEpochSecond() * 1_000_000_000L);
            RetentionEngine engine = engine(store, catalog, root.resolve("v2"), "delete-after-48h", false);
            assertEquals(1, engine.sweep(now));
            assertEquals(0, engine.sweep(now));
            assertFalse(Files.exists(RetentionFiles.instanceDir(root.resolve("v2"), "sample", "default", "pod", "app", "idle")));
            assertFalse(Files.exists(RetentionFiles.instanceDir(root.resolve("v2"), "rollup_1s", "default", "pod", "app", "idle")));
            assertTrue(Files.exists(RetentionFiles.instanceDir(root.resolve("v2"), "rollup_10s", "default", "pod", "app", "idle")));
            assertTrue(Files.exists(RetentionFiles.instanceDir(root.resolve("v2"), "marker", "default", "pod", "app", "idle")));
            assertTrue(Files.exists(RetentionFiles.instanceDir(root.resolve("v2"), "sample", "default", "pod", "app", "live")));
            assertEquals(0L, store.chunkBytes("chunk-idle"));
            assertEquals("summary_only", RetentionLog.state(root.resolve("v2"), "default", "pod", "app", "idle"));
        }
    }

    @Test
    void aDecodingChunkIsLeftOnDisk() throws Exception {
        Path root = Files.createTempDirectory("jafra-retention-decoding");
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        long old = now.minus(Duration.ofHours(49)).getEpochSecond() * 1_000_000_000L;
        try (IngestCatalog catalog = new IngestCatalog(root.resolve("v2"))) {
            ChunkStore store = store(root);
            catalog.ensureInstance("busy", "default", "pod", "app", "rec-busy", old / 1_000_000L, false);
            catalog.touchInstance("busy", old, "unknown");
            byte[] payload = new byte[] {1, 2, 3, 4};
            ChunkMetadata metadata = new ChunkMetadata("chunk-busy", "rec-busy", "c", "default", "uid", "pod", "app", "f.jfr", 0L,
                    payload.length, "sum", old, 1L);
            ChunkStore.IncomingWrite incoming = store.beginWrite(metadata.chunkId());
            incoming.write(payload);
            store.commit(incoming, metadata);
            catalog.enqueue(metadata);
            write(catalog, root.resolve("v2"), "sample", "busy", old);
            assertTrue(catalog.claimAny(now) != null);
            RetentionEngine engine = engine(store, catalog, root.resolve("v2"), "delete-after-48h", false);
            assertEquals(0, engine.sweep(now));
            assertTrue(Files.exists(RetentionFiles.instanceDir(root.resolve("v2"), "sample", "default", "pod", "app", "busy")));
            assertEquals(payload.length, store.chunkBytes("chunk-busy"));
        }
    }

    @Test
    void neverModeAndALiveClockDeleteNothing() throws Exception {
        Path root = Files.createTempDirectory("jafra-retention-never");
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        long old = now.minus(Duration.ofHours(49)).getEpochSecond() * 1_000_000_000L;
        try (IngestCatalog catalog = new IngestCatalog(root.resolve("v2"))) {
            ChunkStore store = store(root);
            seed(store, catalog, root.resolve("v2"), "old", old);
            RetentionEngine engine = engine(store, catalog, root.resolve("v2"), "never", false);
            assertEquals(0, engine.sweep(now));
            assertTrue(Files.exists(RetentionFiles.instanceDir(root.resolve("v2"), "sample", "default", "pod", "app", "old")));
            assertEquals(true, engine.apply("purge-raw", "default", "pod", "app", "old", true, now).get("dry_run"));
            assertTrue(Files.exists(RetentionFiles.instanceDir(root.resolve("v2"), "sample", "default", "pod", "app", "old")));
        }
    }

    @Test
    void clearCacheLeavesATombstoneAndSeriesFallsBack() throws Exception {
        Path root = Files.createTempDirectory("jafra-retention-clear");
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        long old = now.minus(Duration.ofHours(49)).getEpochSecond() * 1_000_000_000L;
        try (IngestCatalog catalog = new IngestCatalog(root.resolve("v2"))) {
            ChunkStore store = store(root);
            seed(store, catalog, root.resolve("v2"), "gone", old);
            RetentionEngine engine = engine(store, catalog, root.resolve("v2"), "delete-after-48h", true);
            assertEquals(1, engine.sweep(now));
            assertFalse(Files.exists(RetentionFiles.instanceDir(root.resolve("v2"), "marker", "default", "pod", "app", "gone")));
            assertTrue(Files.exists(RetentionFiles.instanceDir(root.resolve("v2"), "jvm_instance", "default", "pod", "app", "gone")));
            assertEquals(1, engine.tombstones(Instant.EPOCH).size());
            assertEquals("tombstoned", engine.tombstones(Instant.EPOCH).get(0).get("state"));
        }
        Path seriesRoot = Files.createTempDirectory("jafra-retention-series");
        long bucket = Instant.parse("2026-09-12T12:00:00Z").getEpochSecond() * 1_000_000_000L;
        try (IngestCatalog catalog = new IngestCatalog(seriesRoot.resolve("v2"))) {
            Path file = ParquetTables.partitionFile(seriesRoot.resolve("v2"), "rollup_10s", "default", "pod", "app", "app", bucket, "roll");
            ParquetTables.write(catalog.connection(), file, "rollup_10s", List.of(
                    "namespace", "pod", "container", "jvm_instance_id", "t", "metric", "bucket_t", "n", "sum", "min", "max", "p50", "p90",
                    "p99", "last"), List.of(row("default", "pod", "app", "app", bucket, "cpu.jvm_user", bucket, 1L, 0.2, 0.2, 0.2, 0.2, 0.2, 0.2, 0.2)));
            RetentionLog.upsert(seriesRoot.resolve("v2"),
                    new RetentionLog.Entry("default", "pod", "app", "app", "summary_only", 1L, 1L, 0L, 0L, 1L));
            V2ReadService reads = new V2ReadService(catalog.connection(), seriesRoot.resolve("v2"));
            var body = reads.series(new V2ReadService.Identity("default", "pod", "app", "app"),
                    new V2Window.Range(Instant.parse("2026-09-12T12:00:00Z"), Instant.parse("2026-09-12T12:01:00Z")), "1s", "cpu.jvm_user",
                    true);
            assertEquals("coarser_than_requested", body.get("status"));
            assertEquals("10s", body.get("step"));
            assertEquals(RetentionFiles.REASON, body.get("reason"));
        }
    }

    private static RetentionEngine engine(ChunkStore store, IngestCatalog catalog, Path v2, String mode, boolean clear) {
        return new RetentionEngine(store, catalog, v2, mode, Duration.ofHours(48), clear, Duration.ofMinutes(10));
    }

    private static ChunkStore store(Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        return store;
    }

    private static void seed(ChunkStore store, IngestCatalog catalog, Path v2, String id, long t) throws Exception {
        catalog.ensureInstance(id, "default", "pod", "app", "rec-" + id, t / 1_000_000L, false);
        catalog.touchInstance(id, t, "unknown");
        catalog.record("chunk-" + id, id, "rec-" + id, t, t + 1, "ok", 1L, null);
        byte[] payload = new byte[] {1, 2, 3, 4};
        ChunkStore.IncomingWrite incoming = store.beginWrite("chunk-" + id);
        incoming.write(payload);
        store.commit(incoming, new ChunkMetadata("chunk-" + id, "rec-" + id, "c", "default", "uid", "pod", "app", "f.jfr", 0L,
                payload.length, "sum", t, 1L));
        for (String table : List.of("sample", "rollup_1s", "rollup_10s", "marker", "jvm_instance")) {
            write(catalog, v2, table, id, t);
        }
    }

    private static void write(IngestCatalog catalog, Path v2, String table, String id, long t) throws Exception {
        Path file = ParquetTables.partitionFile(v2, table, "default", "pod", "app", id, t, table);
        ParquetTables.write(catalog.connection(), file, table,
                List.of("namespace", "pod", "container", "jvm_instance_id", "t", "metric", "value"),
                List.of(row("default", "pod", "app", id, t, "cpu.jvm_user", 0.1)));
    }

    private static List<Object> row(Object... values) {
        List<Object> row = new ArrayList<>();
        for (Object value : values) {
            row.add(value);
        }
        return row;
    }

}

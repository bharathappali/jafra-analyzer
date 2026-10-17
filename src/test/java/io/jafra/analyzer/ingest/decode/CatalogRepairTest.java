package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;

class CatalogRepairTest {
    @Test
    void repairFillsIdentityAndRequeuesAnUnreadableChunk() throws Exception {
        Path root = Files.createTempDirectory("jafra-catalog-repair");
        Path v2 = root.resolve("v2");
        try (IngestCatalog catalog = new IngestCatalog(v2)) {
            ChunkStore store = new ChunkStore(root);
            store.recover();
            ChunkMetadata metadata = new ChunkMetadata("chunk-old", "rec", "cluster", "ns", "uid", "pod", "app", "rec", 0L, 3L, "sum",
                    10L, 1L);
            ChunkStore.IncomingWrite incoming = store.beginWrite(metadata.chunkId());
            incoming.write(new byte[] {1, 2, 3});
            store.commit(incoming, metadata);
            catalog.record("chunk-old", null, null, 10L, 11L, "done", 1L, null);
            catalog.record("chunk-bad", "jvm", "rec", 10L, 11L, "done", 1L, null);
            Path broken = v2.resolve("gauge").resolve("namespace=ns").resolve("pod=pod").resolve("container=app")
                    .resolve("jvm_instance_id=jvm").resolve("date=1970-01-01").resolve("chunk-bad.parquet");
            Files.createDirectories(broken.getParent());
            Files.write(broken, new byte[] {1, 2, 3});

            CatalogRepair.repair(catalog, store, v2);

            try (var statement = catalog.connection().prepareStatement(
                    "SELECT namespace, pod, container, status FROM chunk_ingest WHERE chunk_id = 'chunk-old'")) {
                try (var rows = statement.executeQuery()) {
                    assertEquals(true, rows.next());
                    assertEquals("ns", rows.getString(1));
                    assertEquals("pod", rows.getString(2));
                    assertEquals("app", rows.getString(3));
                }
            }
            assertFalse(Files.exists(broken));
            try (var statement = catalog.connection().prepareStatement("SELECT status FROM chunk_ingest WHERE chunk_id = 'chunk-bad'")) {
                try (var rows = statement.executeQuery()) {
                    assertEquals(true, rows.next());
                    assertEquals("pending", rows.getString(1));
                }
            }
        }
    }
}

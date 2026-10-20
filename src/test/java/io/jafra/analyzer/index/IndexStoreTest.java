package io.jafra.analyzer.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexStoreTest {
    @Test
    void createsTheDatabaseAndRejectsADuplicatePrimaryKey(@TempDir Path root) throws Exception {
        IndexStore index = new IndexStore(root);
        try {
            assertTrue(Files.isRegularFile(index.databaseFile()));
            assertNull(index.storageVersion());
            assertTrue(index.insertPending(row("chunk-a", 100, 200)));
            assertTrue(index.exists("chunk-a"));
            assertFalse(index.insertPending(row("chunk-a", 100, 200)));
            assertEquals(1, index.pendingRows().size());
            index.setStorageVersion(2, 3, 1, 0);
            StorageVersion version = index.storageVersion();
            assertEquals(2, version.storageVersion());
            assertEquals(3, version.migrated());
            assertEquals(1, version.quarantined());
            assertEquals(0, version.dropped());
            assertNotNull(version.migratedAt());
        } finally {
            index.close();
        }
    }

    @Test
    void eventRangeOnlyWidens(@TempDir Path root) throws Exception {
        IndexStore index = new IndexStore(root);
        try {
            assertTrue(index.insertPending(row("chunk-a", 100, 200)));
            index.markStored("chunk-a");
            index.updateEventRange("chunk-a", 150, 180);
            ChunkIndexRow narrowed = index.get("chunk-a");
            assertEquals(100, narrowed.minEventStartNs());
            assertEquals(200, narrowed.maxEventEndNs());
            assertNotNull(narrowed.decodedAt());

            index.updateEventRange("chunk-a", 40, 260);
            ChunkIndexRow widened = index.get("chunk-a");
            assertEquals(40, widened.minEventStartNs());
            assertEquals(260, widened.maxEventEndNs());

            long decoded = widened.minEventStartNs();
            index.markDecoded("chunk-a");
            ChunkIndexRow marked = index.get("chunk-a");
            assertEquals(decoded, marked.minEventStartNs());
            assertEquals(260, marked.maxEventEndNs());
            assertNotNull(marked.decodedAt());
        } finally {
            index.close();
        }
    }

    @Test
    void rangeQueryReturnsStoredOverlapOnly(@TempDir Path root) throws Exception {
        IndexStore index = new IndexStore(root);
        try {
            assertTrue(index.insertPending(row("inside", 0, 100)));
            index.markStored("inside");
            assertTrue(index.insertPending(row("outside", 200, 300)));
            index.markStored("outside");
            assertTrue(index.insertPending(row("pending", 0, 100)));
            assertTrue(index.insertPending(otherNamespace()));
            index.markStored("other-ns");

            List<String> ids = index.findRange("default", "payments", "payments-api", "app", 50, 150).stream()
                    .map(ChunkIndexRow::chunkId)
                    .toList();
            assertEquals(List.of("inside"), ids);
            assertEquals(List.of("other", "payments"), index.distinctNamespaces("default"));
            assertEquals(List.of("payments-api"), index.distinctPods("default", "payments"));
            assertEquals(List.of("app"), index.distinctContainers("default", "payments", "payments-api"));
            assertEquals(
                    List.of("inside", "other-ns", "outside"),
                    index.storedByRecording("rec-1").stream()
                            .map(ChunkIndexRow::chunkId)
                            .sorted()
                            .toList());
        } finally {
            index.close();
        }
    }

    private static ChunkIndexRow row(String chunkId, long startNs, long endNs) {
        return new ChunkIndexRow(
                chunkId,
                "rec-1",
                "default",
                "worker-1",
                "payments",
                "payments-api",
                "pod-1",
                "app",
                "default",
                "profile.jfr",
                1,
                0,
                0L,
                128,
                startNs,
                endNs,
                startNs,
                endNs,
                "checksum",
                "default/payments/payments-api/app/chunks/pod-1/default/" + chunkId + ".jfr",
                ChunkIndexRow.STATUS_PENDING,
                null,
                null);
    }

    private static ChunkIndexRow otherNamespace() {
        ChunkIndexRow base = row("other-ns", 0, 100);
        return new ChunkIndexRow(
                base.chunkId(),
                base.recordingId(),
                base.clusterId(),
                base.nodeName(),
                "other",
                base.podName(),
                base.podUid(),
                base.containerName(),
                base.jvmInstance(),
                base.physicalFile(),
                base.fileSequence(),
                base.chunkSequence(),
                base.chunkOffset(),
                base.chunkLength(),
                base.startNs(),
                base.endNs(),
                base.minEventStartNs(),
                base.maxEventEndNs(),
                base.checksum(),
                base.path(),
                base.status(),
                base.receivedAt(),
                base.decodedAt());
    }
}

package io.jafra.analyzer.storage;

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

import com.fasterxml.jackson.databind.ObjectMapper;

import io.jafra.analyzer.index.ChunkIndexRow;
import io.jafra.analyzer.index.IndexStore;

class StorageRecoveryTest {
    @Test
    void pendingRowIsStoredWhenTheFileExistsAndDroppedWhenItDoesNot(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        ChunkMetadata kept = metadata("kept");
        Path payload = ChunkPaths.payload(root, kept);
        Files.createDirectories(payload.getParent());
        Files.write(payload, new byte[] {1, 2, 3});
        assertTrue(store.index().insertPending(row(kept, ChunkPaths.relative(kept))));
        assertTrue(store.index().insertPending(row(metadata("missing"), "default/ns/pod/app/chunks/uid/default/missing.jfr")));

        store.recover();
        assertEquals(ChunkIndexRow.STATUS_STORED, store.index().get("kept").status());
        assertNull(store.index().get("missing"));
        store.close();
    }

    @Test
    void strayPartFilesAreDeleted(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        Path part = root.resolve("data/tmp/leftover.part");
        Files.write(part, new byte[] {9});
        store.recover();
        assertFalse(Files.exists(part));
        store.close();
    }

    @Test
    void missingIndexIsRebuiltFromSidecars(@TempDir Path root) throws Exception {
        ChunkMetadata metadata = metadata("rebuilt");
        Path payload = ChunkPaths.payload(root, metadata);
        Files.createDirectories(payload.getParent());
        Files.write(payload, new byte[] {4, 5, 6});
        new ObjectMapper().writeValue(ChunkPaths.sidecar(root, metadata).toFile(), metadata);

        ChunkStore store = new ChunkStore(root);
        store.recover();
        try {
            ChunkIndexRow row = store.index().get("rebuilt");
            assertNotNull(row);
            assertEquals(ChunkIndexRow.STATUS_STORED, row.status());
            assertNull(row.decodedAt());
            assertEquals(ChunkPaths.relative(metadata), row.path());
        } finally {
            store.close();
        }
    }

    @Test
    void deletingRowsRemoveFilesAndTheIndexRow(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        ChunkMetadata metadata = metadata("old");
        Path payload = ChunkPaths.payload(root, metadata);
        Files.createDirectories(payload.getParent());
        Files.write(payload, new byte[] {1});
        Files.writeString(ChunkPaths.sidecar(root, metadata), "{}");
        ChunkIndexRow pending = row(metadata, ChunkPaths.relative(metadata));
        assertTrue(store.index().insertPending(pending));
        store.index().markStored("old");
        store.index().markDeleting(pending.endNs() + 1);

        store.recover();
        assertNull(store.index().get("old"));
        assertFalse(Files.exists(payload));
        assertFalse(Files.exists(ChunkPaths.sidecar(root, metadata)));
        assertTrue(Files.isDirectory(root.resolve("data/recordings")));
        store.close();
    }

    private static ChunkMetadata metadata(String chunkId) {
        return new ChunkMetadata(
                chunkId,
                "rec",
                "default",
                "ns",
                "uid",
                "pod",
                "app",
                "profile.jfr",
                0,
                3,
                "checksum",
                1_000,
                50);
    }

    private static ChunkIndexRow row(ChunkMetadata metadata, String path) {
        return new ChunkIndexRow(
                metadata.chunkId(),
                metadata.recordingId(),
                "default",
                null,
                metadata.namespace(),
                metadata.podName(),
                metadata.podUid(),
                metadata.containerName(),
                "default",
                metadata.physicalFilename(),
                null,
                null,
                0L,
                metadata.chunkLength(),
                metadata.chunkStartTimeNs(),
                metadata.chunkStartTimeNs() + metadata.chunkDurationNs(),
                metadata.chunkStartTimeNs(),
                metadata.chunkStartTimeNs() + metadata.chunkDurationNs(),
                metadata.checksum(),
                path,
                ChunkIndexRow.STATUS_PENDING,
                null,
                null);
    }
}

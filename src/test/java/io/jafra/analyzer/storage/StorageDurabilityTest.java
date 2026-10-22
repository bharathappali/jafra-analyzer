package io.jafra.analyzer.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jafra.analyzer.index.ChunkIndexRow;

class StorageDurabilityTest {
    @Test
    void ingestCrashAfterEachStepLeavesStoredOrNothing(@TempDir Path root) throws Exception {
        for (String step : java.util.List.of("insert", "json", "payload", "stored")) {
            Path dir = root.resolve(step);
            ChunkStore store = new ChunkStore(dir);
            store.recover();
            byte[] payload = new byte[] {7, 8, 9, (byte) step.length()};
            ChunkMetadata metadata = metadata("chunk-" + step, payload.length);
            try {
                store.setCrashPoint(reached -> {
                    if (step.equals(reached)) {
                        throw new Crash(reached);
                    }
                });
                ChunkStore.IncomingWrite write = store.beginWrite(metadata.chunkId());
                write.write(payload);
                assertThrows(Crash.class, () -> store.commit(write, metadata, "worker-1", 3, 1));
                store.setCrashPoint(reached -> {});
                store.recover();
                boolean early = step.equals("insert") || step.equals("json");
                if (early) {
                    assertNull(store.index().get(metadata.chunkId()));
                    assertFalse(Files.exists(ChunkPaths.payload(dir, metadata)));
                    assertFalse(Files.exists(ChunkPaths.sidecar(dir, metadata)));
                    assertFalse(Files.exists(dir.resolve("data/tmp").resolve(metadata.chunkId() + ".part")));
                } else {
                    assertEquals(ChunkIndexRow.STATUS_STORED, store.index().get(metadata.chunkId()).status());
                    assertArrayEquals(payload, Files.readAllBytes(ChunkPaths.payload(dir, metadata)));
                    assertTrue(Files.isRegularFile(ChunkPaths.sidecar(dir, metadata)));
                    ChunkSidecar.Document sidecar = ChunkSidecar.read(ChunkPaths.sidecar(dir, metadata));
                    assertEquals("worker-1", sidecar.nodeName);
                    assertEquals(3, sidecar.fileSequence);
                    assertEquals(1, sidecar.chunkSequence);
                    assertEquals("default", sidecar.jvmInstance);
                    assertNotNull(sidecar.minEventStartNs);
                    assertNotNull(sidecar.maxEventEndNs);
                }
            } finally {
                store.close();
            }
        }
    }

    @Test
    void decodeHookFailureDoesNotRollBackTheChunk(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        store.onStored(chunkId -> {
            throw new IllegalStateException("decode boom");
        });
        byte[] payload = new byte[] {1, 2, 3, 4};
        ChunkMetadata metadata = metadata("hooked", payload.length);
        try {
            ChunkStore.IncomingWrite write = store.beginWrite(metadata.chunkId());
            write.write(payload);
            store.commit(write, metadata);
            assertEquals(ChunkIndexRow.STATUS_STORED, store.index().get("hooked").status());
            assertArrayEquals(payload, Files.readAllBytes(store.payloadPath("hooked")));
        } finally {
            store.close();
        }
    }

    @Test
    void startupDeletesLeftoverJsonTmpFiles(@TempDir Path root) throws Exception {
        Path recordingTmp = root.resolve("data/recordings/ns/foo.json.tmp");
        Path quarantineTmp = root.resolve("data/quarantine/bar.json.tmp");
        Files.createDirectories(recordingTmp.getParent());
        Files.createDirectories(quarantineTmp.getParent());
        Files.write(recordingTmp, new byte[] {1});
        Files.write(quarantineTmp, new byte[] {2});
        ChunkStore store = new ChunkStore(root);
        try {
            store.recover();
            assertFalse(Files.exists(recordingTmp));
            assertFalse(Files.exists(quarantineTmp));
        } finally {
            store.close();
        }
    }

    @Test
    void missingPayloadPathThrows(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        try {
            store.recover();
            assertThrows(NoSuchFileException.class, () -> store.payloadPath("missing"));
        } finally {
            store.close();
        }
    }

    @Test
    void repairRewritesAMissingSidecarFromTheRow(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        byte[] payload = new byte[] {9, 9, 9};
        ChunkMetadata metadata = metadata("sidecar", payload.length);
        try {
            ChunkStore.IncomingWrite write = store.beginWrite(metadata.chunkId());
            write.write(payload);
            store.commit(write, metadata, "node-a", 2, 4);
            Files.delete(ChunkPaths.sidecar(root, metadata));
            store.repairMissingSidecars();
            ChunkSidecar.Document document = ChunkSidecar.read(ChunkPaths.sidecar(root, metadata));
            assertEquals("sidecar", document.chunkId);
            assertEquals("node-a", document.nodeName);
            assertEquals(2, document.fileSequence);
            assertEquals(4, document.chunkSequence);
            assertNotNull(document.minEventStartNs);
        } finally {
            store.close();
        }
    }

    @Test
    void failedMigrationStaysNotReadyUntilItSucceeds(@TempDir Path root) throws Exception {
        byte[] payload = new byte[] {1, 2, 3, 4};
        ChunkMetadata metadata = new ChunkMetadata(
                "slow-chunk",
                "rec-slow",
                "default",
                "ns",
                "uid",
                "pod",
                "app",
                "profile.jfr",
                0,
                payload.length,
                "checksum",
                1_000_000_000L,
                10);
        Path chunks = root.resolve("chunks");
        Files.createDirectories(chunks);
        Files.write(chunks.resolve("slow-chunk.jfr"), payload);
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(chunks.resolve("slow-chunk.meta").toFile(), metadata);

        ChunkStore store = new ChunkStore(root);
        try {
            store.recoverFast();
            assertFalse(store.ready());
            assertThrows(java.io.IOException.class, () -> store.runMigration(phase -> {
                throw new java.io.IOException("boom");
            }));
            assertFalse(store.ready());
            assertEquals(60, io.jafra.analyzer.storage.StorageMigration.RETRY_DELAY_SECONDS);
            store.runMigration(phase -> {});
            assertTrue(store.ready());
            assertEquals(ChunkIndexRow.STATUS_STORED, store.index().get("slow-chunk").status());
            assertArrayEquals(payload, Files.readAllBytes(ChunkPaths.payload(root, metadata)));
        } finally {
            store.close();
        }
    }

    private static ChunkMetadata metadata(String chunkId, int length) {
        return new ChunkMetadata(
                chunkId,
                "rec-" + chunkId,
                "default",
                "ns",
                "uid",
                "pod",
                "app",
                "profile.jfr",
                0,
                length,
                "checksum",
                1_000,
                50);
    }

    private static final class Crash extends Error {
        Crash(String step) {
            super(step);
        }
    }
}

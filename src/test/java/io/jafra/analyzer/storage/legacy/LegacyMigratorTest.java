package io.jafra.analyzer.storage.legacy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.jafra.analyzer.index.ChunkIndexRow;
import io.jafra.analyzer.index.IndexStore;
import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkPaths;
import io.jafra.analyzer.storage.ChunkStore;

class LegacyMigratorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void migratesV0AndV1Fixtures(@TempDir Path root) throws Exception {
        byte[] named = new byte[] {1, 2, 3, 4};
        byte[] timed = headerPayload();
        byte[] shared = new byte[] {7, 7, 7, 7};
        byte[] mismatched = new byte[] {9};

        Files.createDirectories(root.resolve("identities"));
        MAPPER.writeValue(root.resolve("identities/uid-pay.json").toFile(), Map.of(
                "namespace", "pay",
                "podName", "pay-gateway",
                "podUid", "uid-pay"));

        ChunkMetadata fromIdentity = chunk("from-identity", "", "uid-pay", 1_000, 10, named.length);
        writeV0(root, new ChunkMetadata(
                fromIdentity.chunkId(),
                fromIdentity.recordingId(),
                "",
                fromIdentity.namespace(),
                fromIdentity.podUid(),
                fromIdentity.podName(),
                fromIdentity.containerName(),
                fromIdentity.physicalFilename(),
                fromIdentity.chunkOffset(),
                fromIdentity.chunkLength(),
                fromIdentity.checksum(),
                fromIdentity.chunkStartTimeNs(),
                fromIdentity.chunkDurationNs()), named);
        writeV0(root, chunk("timed", "auth-cache", "uid-time", 0, 0, timed.length), timed);
        writeV0(root, chunk("shared", "auth-cache", "uid-shared", 2_000_000_000L, 10, shared.length), shared);
        writeV1(root, chunk("shared", "auth-cache", "uid-shared", 2_000_000_000L, 10, shared.length), shared);
        writeV0(root, chunk("orphan-meta", "auth-cache", "uid-meta", 1_000, 10, 4), null);
        Files.createDirectories(root.resolve("chunks"));
        Files.write(root.resolve("chunks/orphan-payload.jfr"), new byte[] {5, 5});
        writeV0(root, chunk("bad-size", "auth-cache", "uid-size", 1_000, 10, 99), mismatched);

        Files.createDirectories(root.resolve("stitch-cache"));
        Files.write(root.resolve("stitch-cache/old.jfr"), new byte[] {1});
        Path stitched = root.resolve("recordings/pay/auth-cache/profile.jfr");
        Files.createDirectories(stitched);
        Files.write(stitched.resolve("stitched.jfr"), new byte[] {2});
        Files.writeString(stitched.resolve("manifest.json"), "{}");

        ChunkStore store = new ChunkStore(root);
        store.recover();
        try {
            assertEquals("pay-gateway", store.index().get("from-identity").podName());
            assertEquals("default", store.index().get("from-identity").clusterId());
            assertEquals(1_750_000_000_000_000_000L, store.index().get("timed").startNs());
            assertEquals(ChunkIndexRow.STATUS_STORED, store.index().get("shared").status());
            assertEquals(ChunkPaths.relative(chunk("shared", "auth-cache", "uid-shared", 2_000_000_000L, 10, shared.length)),
                    store.index().get("shared").path());
            assertFalse(Files.exists(root.resolve("chunks")));
            assertFalse(Files.exists(root.resolve("meta")));
            assertFalse(Files.exists(root.resolve("stitch-cache")));
            assertFalse(Files.exists(stitched.resolve("stitched.jfr")));
            assertTrue(Files.isRegularFile(root.resolve("identities/uid-pay.json")));
            assertTrue(Files.isRegularFile(root.resolve("data/quarantine/orphan-payload.jfr")));
            assertTrue(Files.readString(root.resolve("data/quarantine/orphan-payload.reason.txt")).contains("orphan"));
            assertFalse(Files.exists(root.resolve("data/quarantine/orphan-meta.json")));
            assertTrue(Files.isRegularFile(root.resolve("data/quarantine/bad-size.jfr")));
            assertTrue(Files.readString(root.resolve("data/quarantine/bad-size.reason.txt")).contains("chunk_length"));
            assertEquals(3, store.index().storageVersion().migrated());
            assertEquals(2, store.index().storageVersion().quarantined());
            assertEquals(1, store.index().storageVersion().dropped());
            List<String> ids = store.index().findRange("payments", "pay", null, null, 0, Long.MAX_VALUE).stream()
                    .map(ChunkIndexRow::chunkId)
                    .toList();
            assertTrue(ids.contains("timed"));
            assertTrue(ids.contains("shared"));
            assertTrue(store.index().findRange("default", "pay", "pay-gateway", null, 0, Long.MAX_VALUE).stream()
                    .map(ChunkIndexRow::chunkId)
                    .toList()
                    .contains("from-identity"));

            long migrated = store.index().storageVersion().migrated();
            store.recover();
            assertEquals(migrated, store.index().storageVersion().migrated());
            assertFalse(LegacyMigrator.hasLegacy(root));
            assertEquals(ChunkPaths.payload(root, chunk("shared", "auth-cache", "uid-shared", 2_000_000_000L, 10, shared.length)),
                    store.payloadPath("shared"));
        } finally {
            store.close();
        }
    }

    @Test
    void crashThenRerunMatchesACleanMigration(@TempDir Path crashed, @TempDir Path clean) throws Exception {
        byte[] payload = new byte[] {4, 5, 6};
        ChunkMetadata metadata = chunk("resume", "auth-cache", "uid-resume", 3_000_000_000L, 10, payload.length);
        writeV0(crashed, metadata, payload);
        writeV0(clean, metadata, payload);

        IndexStore crashedIndex = new IndexStore(crashed);
        try {
            assertThrows(IOException.class, () -> new LegacyMigrator(phase -> {
                throw new IOException("killed at " + phase);
            }).migrate(crashed, crashedIndex, Map.of()));
            assertEquals(ChunkIndexRow.STATUS_PENDING, crashedIndex.get("resume").status());
            assertTrue(Files.isRegularFile(crashed.resolve("chunks/resume.jfr")));

            new LegacyMigrator().migrate(crashed, crashedIndex, Map.of());
            assertEquals(ChunkIndexRow.STATUS_STORED, crashedIndex.get("resume").status());
            assertFalse(Files.exists(crashed.resolve("chunks/resume.jfr")));
            assertArrayEquals(payload, Files.readAllBytes(ChunkPaths.payload(crashed, metadata)));
            String crashedPath = crashedIndex.get("resume").path();
            crashedIndex.close();

            IndexStore cleanIndex = new IndexStore(clean);
            try {
                new LegacyMigrator().migrate(clean, cleanIndex, Map.of());
                assertArrayEquals(
                        Files.readAllBytes(ChunkPaths.payload(clean, metadata)),
                        Files.readAllBytes(ChunkPaths.payload(crashed, metadata)));
                assertEquals(crashedPath, cleanIndex.get("resume").path());
            } finally {
                cleanIndex.close();
            }
        } finally {
            crashedIndex.close();
        }
    }

    @Test
    void quarantineKeepsEveryOriginalByte(@TempDir Path root) throws Exception {
        byte[] orphan = new byte[] {8, 8, 8};
        byte[] badPayload = new byte[] {3, 3};
        byte[] badMeta = "not-json".getBytes();
        byte[] mismatchPayload = new byte[] {4};
        byte[] v0Payload = new byte[] {1, 1, 1};
        byte[] v1Payload = new byte[] {2, 2, 2};

        ChunkMetadata orphanMeta = chunk("orphan-v1", "auth-cache", "uid-o", 5_000_000_000L, 10, orphan.length);
        Path orphanPath = root.resolve("data").resolve(ChunkFileName.bucket(orphanMeta)).resolve(ChunkFileName.fileName(orphanMeta));
        Files.createDirectories(orphanPath.getParent());
        Files.write(orphanPath, orphan);

        ChunkMetadata bad = chunk("bad-meta-v1", "auth-cache", "uid-b", 6_000_000_000L, 10, badPayload.length);
        Path badPayloadPath = root.resolve("data").resolve(ChunkFileName.bucket(bad)).resolve(ChunkFileName.fileName(bad));
        Path badMetaPath = root.resolve("meta").resolve(ChunkFileName.bucket(bad)).resolve(ChunkFileName.fileName(bad));
        Files.createDirectories(badPayloadPath.getParent());
        Files.createDirectories(badMetaPath.getParent());
        Files.write(badPayloadPath, badPayload);
        Files.write(badMetaPath, badMeta);

        ChunkMetadata mismatch = chunk("mismatch-v1", "auth-cache", "uid-m", 7_000_000_000L, 10, 99);
        writeV1(root, mismatch, mismatchPayload);
        byte[] mismatchMeta = Files.readAllBytes(
                root.resolve("meta").resolve(ChunkFileName.bucket(mismatch)).resolve(ChunkFileName.fileName(mismatch)));

        ChunkMetadata v0 = chunk("same-id", "auth-cache", "uid-s", 8_000_000_000L, 10, 40);
        writeV0(root, v0, v0Payload);
        byte[] v0Meta = Files.readAllBytes(root.resolve("chunks/same-id.meta"));
        ChunkMetadata v1 = new ChunkMetadata(
                v0.chunkId(),
                v0.recordingId(),
                v0.clusterId(),
                v0.namespace(),
                v0.podUid(),
                v0.podName(),
                v0.containerName(),
                v0.physicalFilename(),
                v0.chunkOffset(),
                41,
                "other-checksum",
                v0.chunkStartTimeNs(),
                v0.chunkDurationNs());
        writeV1(root, v1, v1Payload);
        byte[] v1Meta = Files.readAllBytes(
                root.resolve("meta").resolve(ChunkFileName.bucket(v1)).resolve(ChunkFileName.fileName(v1)));

        IndexStore index = new IndexStore(root);
        try {
            new LegacyMigrator().migrate(root, index, Map.of());
            List<byte[]> saved = quarantineBytes(root.resolve("data/quarantine"));
            assertOnce(saved, orphan);
            assertOnce(saved, badPayload);
            assertOnce(saved, badMeta);
            assertOnce(saved, mismatchPayload);
            assertOnce(saved, mismatchMeta);
            assertOnce(saved, v0Payload);
            assertOnce(saved, v1Payload);
            assertOnce(saved, v0Meta);
            assertOnce(saved, v1Meta);
        } finally {
            index.close();
        }
    }

    @Test
    void migrationCrashAfterEachStepLeavesStoredOrLegacy(@TempDir Path root) throws Exception {
        for (String step : List.of("after-insert", "after-json", "after-payload", "after-stored")) {
            Path dir = root.resolve(step);
            byte[] payload = new byte[] {4, 5, 6, (byte) step.length()};
            ChunkMetadata metadata = chunk("resume", "auth-cache", "uid-resume", 3_000_000_000L, 10, payload.length);
            writeV0(dir, metadata, payload);
            byte[] original = payload.clone();
            byte[] originalMeta = Files.readAllBytes(dir.resolve("chunks/resume.meta"));
            ChunkStore store = new ChunkStore(dir);
            try {
                store.recoverFast();
                assertThrows(IOException.class, () -> store.runMigration(phase -> {
                    if (phase.startsWith(step + ":")) {
                        throw new IOException(step);
                    }
                }));
                store.recoverFast();
                boolean early = step.equals("after-insert") || step.equals("after-json");
                if (early) {
                    assertNull(store.index().get("resume"));
                    assertFalse(Files.exists(ChunkPaths.payload(dir, metadata)));
                    assertFalse(Files.exists(ChunkPaths.sidecar(dir, metadata)));
                    assertArrayEquals(original, Files.readAllBytes(dir.resolve("chunks/resume.jfr")));
                    assertArrayEquals(originalMeta, Files.readAllBytes(dir.resolve("chunks/resume.meta")));
                } else {
                    assertEquals(ChunkIndexRow.STATUS_STORED, store.index().get("resume").status());
                    assertArrayEquals(original, Files.readAllBytes(ChunkPaths.payload(dir, metadata)));
                    assertTrue(Files.isRegularFile(ChunkPaths.sidecar(dir, metadata)));
                }
                store.runMigration(phase -> {});
                assertEquals(ChunkIndexRow.STATUS_STORED, store.index().get("resume").status());
                assertArrayEquals(original, Files.readAllBytes(ChunkPaths.payload(dir, metadata)));
                assertTrue(Files.isRegularFile(ChunkPaths.sidecar(dir, metadata)));
                assertFalse(Files.exists(dir.resolve("chunks/resume.jfr")));
            } finally {
                store.close();
            }
        }
    }

    private static List<byte[]> quarantineBytes(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().endsWith(".reason.txt"))
                    .map(path -> {
                        try {
                            return Files.readAllBytes(path);
                        } catch (IOException error) {
                            throw new java.io.UncheckedIOException(error);
                        }
                    })
                    .toList();
        }
    }

    private static void assertOnce(List<byte[]> saved, byte[] expected) {
        int found = 0;
        for (byte[] candidate : saved) {
            if (java.util.Arrays.equals(candidate, expected)) {
                found++;
            }
        }
        assertEquals(1, found, "expected one quarantine copy of " + expected.length + " bytes");
    }

    private static void writeV0(Path root, ChunkMetadata metadata, byte[] payload) throws IOException {
        Path chunks = root.resolve("chunks");
        Files.createDirectories(chunks);
        MAPPER.writeValue(chunks.resolve(metadata.chunkId() + ".meta").toFile(), metadata);
        if (payload != null) {
            Files.write(chunks.resolve(metadata.chunkId() + ".jfr"), payload);
        }
    }

    private static void writeV1(Path root, ChunkMetadata metadata, byte[] payload) throws IOException {
        String name = ChunkFileName.fileName(metadata);
        String bucket = ChunkFileName.bucket(metadata);
        Path payloadPath = root.resolve("data").resolve(bucket).resolve(name);
        Path metaPath = root.resolve("meta").resolve(bucket).resolve(name);
        Files.createDirectories(payloadPath.getParent());
        Files.createDirectories(metaPath.getParent());
        Files.write(payloadPath, payload);
        MAPPER.writeValue(metaPath.toFile(), metadata);
    }

    private static ChunkMetadata chunk(
            String chunkId, String pod, String podUid, long startNs, long durationNs, long length) {
        return new ChunkMetadata(
                chunkId,
                "rec-" + chunkId,
                "payments",
                "pay",
                podUid,
                pod,
                "app",
                "profile.jfr",
                0,
                length,
                "checksum",
                startNs,
                durationNs);
    }

    private static byte[] headerPayload() {
        byte[] payload = new byte[68];
        ByteBuffer header = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        header.put(new byte[] {'F', 'L', 'R', 0});
        header.putInt(2);
        header.putLong(68);
        header.putLong(68);
        header.putLong(68);
        header.putLong(1_750_000_000_000_000_000L);
        header.putLong(5_000_000_000L);
        return payload;
    }
}

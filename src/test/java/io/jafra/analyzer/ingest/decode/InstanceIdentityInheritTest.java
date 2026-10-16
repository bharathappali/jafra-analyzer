package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.identity.JvmInstanceIds;
import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;

class InstanceIdentityInheritTest {
    private static final long START_MS = 1_726_000_000_000L;
    private static final long PID = 1L;
    private static final long T0 = START_MS * 1_000_000L;
    private static final long CHUNK_NS = 5_000_000_000L;

    @Test
    void rotatedFileWithoutJvmInformationKeepsTheStableInstance() throws Exception {
        try (Harness harness = Harness.open()) {
            ChunkMetadata first = meta("c0", "cluster/pod-uid/auth-cache/profile-0.jfr", T0);
            ChunkDecodeService.Resolved stable = harness.resolve(first, withJvm(START_MS, PID), T0);
            harness.catalog.touchInstance(stable.id(), T0 + CHUNK_NS, null);
            ChunkDecodeService.Resolved rotated = harness.resolve(
                    meta("c1", "cluster/pod-uid/auth-cache/profile-1.jfr", T0 + CHUNK_NS),
                    new JfrChunkReader.ReadChunk(),
                    T0 + CHUNK_NS);

            assertEquals(stable.id(), rotated.id());
            assertFalse(rotated.provisional());
            assertEquals(1, rotated.state().instanceSeq());
            assertEquals(1, harness.catalog.instances().size());
            assertEquals(expectedRealId(START_MS, PID), stable.id());
        }
    }

    @Test
    void realIdentityRekeysAProvisionalFromAnotherRecording() throws Exception {
        try (Harness harness = Harness.open()) {
            ChunkMetadata early = meta("c0", "cluster/pod-uid/auth-cache/profile-0.jfr", T0);
            ChunkDecodeService.Resolved provisional = harness.resolve(early, new JfrChunkReader.ReadChunk(), T0);
            assertTrue(provisional.provisional());
            harness.catalog.record(early.chunkId(), provisional.id(), early.recordingId(), T0, T0 + CHUNK_NS, "ok", 1L, null);

            ChunkDecodeService.Resolved real = harness.resolve(
                    meta("c1", "cluster/pod-uid/auth-cache/profile-1.jfr", T0 + CHUNK_NS), withJvm(START_MS, PID), T0 + CHUNK_NS);

            assertEquals(expectedRealId(START_MS, PID), real.id());
            assertNotEquals(provisional.id(), real.id());
            assertEquals(1, real.state().instanceSeq());
            assertEquals(1, harness.catalog.instances().size());
            assertEquals(real.id(), harness.catalog.instances().get(0).id());
            assertEquals(real.id(), harness.ingestedInstance("c0"));
        }
    }

    @Test
    void aGapWithoutJvmFactsStaysOnTheLiveInstance() throws Exception {
        try (Harness harness = Harness.open()) {
            ChunkDecodeService.Resolved previous = harness.resolve(
                    meta("old", "cluster/pod-uid/auth-cache/profile-0.jfr", T0), withJvm(START_MS, PID), T0);
            harness.catalog.touchInstance(previous.id(), T0 + CHUNK_NS, null);

            long gapAt = T0 + CHUNK_NS + 60_000_000_000L;
            ChunkMetadata early = meta("early", "cluster/pod-uid/auth-cache/profile-4.jfr", gapAt);
            ChunkDecodeService.Resolved unknown = harness.resolve(early, new JfrChunkReader.ReadChunk(), gapAt);
            assertFalse(unknown.provisional());
            assertEquals(previous.id(), unknown.id());
            harness.catalog.record(early.chunkId(), previous.id(), early.recordingId(), gapAt, gapAt + CHUNK_NS, "ok", 1L, null);

            long newStartMs = (gapAt + CHUNK_NS) / 1_000_000L;
            ChunkDecodeService.Resolved identified = harness.resolve(
                    meta("named", "cluster/pod-uid/auth-cache/profile-5.jfr", newStartMs * 1_000_000L),
                    withJvm(newStartMs, 2L),
                    newStartMs * 1_000_000L);

            assertEquals(expectedRealId(newStartMs, 2L), identified.id());
            assertEquals(previous.id(), harness.ingestedInstance("early"));
            assertEquals(2, identified.state().instanceSeq());
            assertEquals(previous.id(), identified.state().previousInstanceId());
            assertEquals(2, harness.catalog.instances().size());
        }
    }

    @Test
    void chunksAttachedAcrossARestartMoveWhenTheNewStartArrives() throws Exception {
        try (Harness harness = Harness.open()) {
            ChunkMetadata oldChunk = meta("old", "cluster/pod-uid/auth-cache/profile-0.jfr", T0);
            ChunkDecodeService.Resolved previous = harness.resolve(oldChunk, withJvm(START_MS, PID), T0);
            long oldEnd = T0 + CHUNK_NS;
            harness.catalog.record(oldChunk.chunkId(), previous.id(), oldChunk.recordingId(), T0, oldEnd, "ok", 1L, null);
            harness.catalog.touchInstance(previous.id(), oldEnd, null);
            writeGauge(harness, previous.id(), "old", T0);

            long attachedAt = oldEnd + 1_000_000_000L;
            ChunkMetadata attached = meta("attached", "cluster/pod-uid/auth-cache/profile-1.jfr", attachedAt);
            ChunkDecodeService.Resolved inherited = harness.resolve(attached, new JfrChunkReader.ReadChunk(), attachedAt);
            assertEquals(previous.id(), inherited.id());
            harness.catalog.record(
                    attached.chunkId(), previous.id(), attached.recordingId(), attachedAt, attachedAt + CHUNK_NS, "ok", 1L, null);
            harness.catalog.touchInstance(previous.id(), attachedAt + CHUNK_NS, null);
            writeGauge(harness, previous.id(), "attached", attachedAt);

            long newStartMs = attachedAt / 1_000_000L;
            ChunkDecodeService.Resolved identified = harness.resolve(
                    meta("named", "cluster/pod-uid/auth-cache/profile-1.jfr", attachedAt + CHUNK_NS),
                    withJvm(newStartMs, 2L),
                    attachedAt + CHUNK_NS);

            assertEquals(expectedRealId(newStartMs, 2L), identified.id());
            assertEquals(identified.id(), harness.ingestedInstance("attached"));
            assertEquals(previous.id(), harness.ingestedInstance("old"));
            assertEquals(oldEnd, harness.lastT(previous.id()));
            assertEquals(identified.id(), parquetInstance(gauge(harness, identified.id(), "attached", attachedAt)));
            assertFalse(Files.exists(gauge(harness, previous.id(), "attached", attachedAt)));
            assertEquals(previous.id(), parquetInstance(gauge(harness, previous.id(), "old", T0)));
        }
    }

    @Test
    void nextSequenceFollowsHighestInstanceSeq() throws Exception {
        try (Harness harness = Harness.open()) {
            harness.catalog.ensureInstance("real-old", "default", "auth-cache", "auth-cache", "rec-a", 5_000L, false);
            harness.catalog.touchInstance("real-old", 1_000L, "unknown");
            IngestCatalog.InstanceState phantom = harness.catalog.ensureInstance(
                    "provisional", "default", "auth-cache", "auth-cache", "rec-b", 0L, true);
            assertEquals(2, phantom.instanceSeq());
            harness.catalog.touchInstance("provisional", 9_000L, "unknown");

            IngestCatalog.InstanceState restarted = harness.catalog.ensureInstance(
                    "real-new", "default", "auth-cache", "auth-cache", "rec-c", 9_000L, false);
            assertEquals(3, restarted.instanceSeq());
            assertEquals("provisional", restarted.previousInstanceId());
        }
    }

    private static String expectedRealId(long startMs, long pid) {
        return JvmInstanceIds.of("default", "auth-cache", "auth-cache", "pod-uid", startMs, pid);
    }

    private static JfrChunkReader.ReadChunk withJvm(long startMs, long pid) {
        JfrChunkReader.ReadChunk chunk = new JfrChunkReader.ReadChunk();
        chunk.jvm = new JfrChunkReader.JvmFacts("21.0.2", "HotSpot", startMs, pid);
        return chunk;
    }

    private static void writeGauge(Harness harness, String instanceId, String chunkId, long t) throws Exception {
        Path file = gauge(harness, instanceId, chunkId, t);
        ParquetTables.write(harness.catalog.connection(), file, "gauge",
                List.of("namespace", "pod", "container", "jvm_instance_id", "t", "metric", "value"),
                List.of(List.of("default", "auth-cache", "auth-cache", instanceId, t, "cpu.jvm_user", 0.2d)));
    }

    private static Path gauge(Harness harness, String instanceId, String chunkId, long t) throws Exception {
        return ParquetTables.partitionFile(harness.v2, "gauge", "default", "auth-cache", "auth-cache", instanceId, t, chunkId);
    }

    private static String parquetInstance(Path file) throws Exception {
        IngestCatalog.ensureDriver();
        String path = file.toAbsolutePath().toString().replace("'", "''");
        try (var connection = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT jvm_instance_id FROM read_parquet('" + path + "')")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static ChunkMetadata meta(String chunkId, String recordingId, long tStart) {
        return new ChunkMetadata(
                chunkId,
                recordingId,
                "cluster",
                "default",
                "pod-uid",
                "auth-cache",
                "auth-cache",
                recordingId,
                0L,
                1L,
                "checksum",
                tStart,
                1_000L);
    }

    private static final class Harness implements AutoCloseable {
        final IngestCatalog catalog;
        final Path v2;
        private final ChunkDecodeService service;

        private Harness(IngestCatalog catalog, ChunkDecodeService service, Path v2) {
            this.catalog = catalog;
            this.service = service;
            this.v2 = v2;
        }

        static Harness open() throws Exception {
            Path root = Files.createTempDirectory("jafra-instance-id");
            Path v2 = root.resolve("v2");
            IngestCatalog catalog = new IngestCatalog(v2);
            return new Harness(catalog, new ChunkDecodeService(new ChunkStore(root), catalog, v2), v2);
        }

        long lastT(String instanceId) throws Exception {
            for (IngestCatalog.ListedInstance instance : catalog.instances()) {
                if (instance.id().equals(instanceId)) {
                    return instance.lastTNs();
                }
            }
            throw new AssertionError("missing " + instanceId);
        }

        ChunkDecodeService.Resolved resolve(ChunkMetadata metadata, JfrChunkReader.ReadChunk chunk, long tStart)
                throws Exception {
            return service.resolve(metadata, chunk, tStart);
        }

        String ingestedInstance(String chunkId) throws Exception {
            try (PreparedStatement statement = catalog.connection().prepareStatement(
                    "SELECT jvm_instance_id FROM chunk_ingest WHERE chunk_id = ?")) {
                statement.setString(1, chunkId);
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    return rows.getString(1);
                }
            }
        }

        @Override
        public void close() throws Exception {
            catalog.close();
        }
    }
}

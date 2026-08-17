package io.jafra.analyzer.recordings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;

class RecordingCatalogTest {
    @Test
    void listsRecordingsByNamespacePodAndContainer(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        persist(store, "alpha-0", "ns-a", "pod-a", "app", "profile-0.jfr", new byte[] {1, 2, 3});
        persist(store, "alpha-1", "ns-a", "pod-a", "app", "profile-1.jfr", new byte[] {4, 5});
        persist(store, "beta-0", "ns-b", "pod-b", "worker", "profile-0.jfr", new byte[] {9});

        RecordingCatalog catalog = new RecordingCatalog(store);
        RecordingCatalog.RecordingListResponse all = catalog.list(null, null, null);
        assertEquals(2, all.workloads().size());

        RecordingCatalog.RecordingListResponse nsA = catalog.list("ns-a", null, null);
        assertEquals(1, nsA.workloads().size());
        RecordingCatalog.WorkloadRecordings workload = nsA.workloads().getFirst();
        assertEquals("pod-a", workload.pod());
        assertEquals("app", workload.container());
        assertEquals(List.of("profile-0.jfr", "profile-1.jfr"),
                workload.recordings().stream().map(RecordingCatalog.RecordingSummary::filename).toList());
        assertTrue(workload.recordings().getFirst().reportUrl().contains("/namespaces/ns-a/pods/pod-a/containers/app/"));

        RecordingCatalog.RecordingListResponse byPod = catalog.list(null, "pod-b", null);
        assertEquals("worker", byPod.workloads().getFirst().container());
        assertEquals(1, byPod.workloads().getFirst().recordings().size());
    }

    @Test
    void defaultReportUsesTheLastClosedRotation(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        persist(store, "c0", "ns-a", "pod-a", "app", "profile-0.jfr", new byte[] {1});
        persist(store, "c1", "ns-a", "pod-a", "app", "profile-1.jfr", new byte[] {2});
        persist(store, "c2", "ns-a", "pod-a", "app", "profile-2.jfr", new byte[] {3});
        RecordingCatalog catalog = new RecordingCatalog(store);
        assertEquals("profile-1.jfr", catalog.latestClosed("ns-a", "pod-a", "app").summary().filename());
    }

    @Test
    void listsStartAndStopFromJfrHeaders(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        Instant start = Instant.parse("2026-08-17T09:00:00Z");
        persist(store, "t0", "ns-a", "pod-a", "app", "profile-0.jfr", timedJfr(start, Duration.ofMinutes(1)));

        RecordingCatalog catalog = new RecordingCatalog(store);
        RecordingCatalog.RecordingSummary summary = catalog.list("ns-a", "pod-a", "app")
                .workloads()
                .getFirst()
                .recordings()
                .getFirst();
        assertEquals("2026-08-17T09:00:00Z", summary.start());
        assertEquals("2026-08-17T09:01:00Z", summary.end());
    }

    @Test
    void windowMergesOverlappingRotations(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        Instant t0 = Instant.parse("2026-08-17T09:00:00Z");
        Instant t1 = Instant.parse("2026-08-17T09:01:00Z");
        Instant t2 = Instant.parse("2026-08-17T09:08:00Z");
        Instant now = Instant.parse("2026-08-17T09:10:00Z");
        persist(store, "c0", "ns-a", "pod-a", "app", "profile-0.jfr", timedJfr(t0, Duration.ofMinutes(1)));
        persist(store, "c1", "ns-a", "pod-a", "app", "profile-1.jfr", timedJfr(t1, Duration.ofMinutes(1)));
        persist(store, "c2", "ns-a", "pod-a", "app", "profile-2.jfr", timedJfr(t2, Duration.ofMinutes(1)));
        RecordingCatalog catalog = new RecordingCatalog(store);

        try (RecordingCatalog.WindowSelection lastFive = catalog.openWindow(
                        "ns-a", "pod-a", "app", ReportWindow.parse(now, "5m", null, null, null, null))
                .orElseThrow()) {
            assertEquals(List.of("profile-2.jfr"), filenames(lastFive));
        }

        try (RecordingCatalog.WindowSelection lastFifteen = catalog.openWindow(
                        "ns-a", "pod-a", "app", ReportWindow.parse(now, "15mins", null, null, null, null))
                .orElseThrow()) {
            assertEquals(List.of("profile-0.jfr", "profile-1.jfr", "profile-2.jfr"), filenames(lastFifteen));
            assertTrue(lastFifteen.temporary());
            assertTrue(lastFifteen.jfr().toFile().length() > 0);
        }

        try (RecordingCatalog.WindowSelection ranged = catalog.openWindow(
                        "ns-a",
                        "pod-a",
                        "app",
                        ReportWindow.parse(now, null, "2026-08-17T09:00:30Z", "2026-08-17T09:01:30Z", null, null))
                .orElseThrow()) {
            assertEquals(List.of("profile-0.jfr", "profile-1.jfr"), filenames(ranged));
        }

        try (RecordingCatalog.WindowSelection before = catalog.openWindow(
                        "ns-a", "pod-a", "app", ReportWindow.parse(now, null, null, null, "2026-08-17T09:01:00Z", null))
                .orElseThrow()) {
            assertEquals(List.of("profile-0.jfr"), filenames(before));
        }

        try (RecordingCatalog.WindowSelection after = catalog.openWindow(
                        "ns-a", "pod-a", "app", ReportWindow.parse(now, null, null, null, null, "2026-08-17T09:08:00Z"))
                .orElseThrow()) {
            assertEquals(List.of("profile-2.jfr"), filenames(after));
        }

        Optional<RecordingCatalog.WindowSelection> missing = catalog.openWindow(
                "ns-a",
                "pod-a",
                "app",
                ReportWindow.parse(now, null, "2020-01-01T00:00:00Z", "2020-01-02T00:00:00Z", null, null));
        assertTrue(missing.isEmpty());
    }

    @Test
    void unnamedRecordingsAreOmittedUntilPodNameIsKnown(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        persist(store, "anon", "default", "", "app", "profile-0.jfr", new byte[] {1, 2});
        RecordingCatalog catalog = new RecordingCatalog(store);
        assertTrue(catalog.list("default", null, "app").workloads().isEmpty());

        persist(store, "named", "default", "app-pod", "app", "profile-1.jfr", new byte[] {3});
        store.rememberPodName("default", "uid", "app-pod");
        RecordingCatalog.RecordingListResponse listed = catalog.list("default", "app-pod", "app");
        assertEquals(2, listed.workloads().getFirst().recordings().size());
    }

    private static void persist(
            ChunkStore store,
            String chunkId,
            String namespace,
            String podName,
            String container,
            String filename,
            byte[] payload)
            throws Exception {
        String recordingId = "local-demo/%s/%s/%s".formatted(
                podName.isBlank() ? "uid" : "uid", container, filename);
        ChunkMetadata metadata = new ChunkMetadata(
                chunkId,
                recordingId,
                "local-demo",
                namespace,
                "uid",
                podName,
                container,
                filename,
                0,
                payload.length,
                "checksum");
        ChunkStore.IncomingWrite write = store.beginWrite(chunkId);
        write.write(payload);
        store.commit(write, metadata);
    }

    private static byte[] timedJfr(Instant start, Duration duration) {
        int size = 128;
        byte[] payload = new byte[size];
        long startNs = start.getEpochSecond() * 1_000_000_000L + start.getNano();
        System.arraycopy(JfrTimeRange.header(size, startNs, duration.toNanos()), 0, payload, 0, JfrTimeRange.HEADER_SIZE);
        return payload;
    }

    private static List<String> filenames(RecordingCatalog.WindowSelection selected) {
        return selected.recordings().stream().map(recording -> recording.summary().filename()).toList();
    }
}

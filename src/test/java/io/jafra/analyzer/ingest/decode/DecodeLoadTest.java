package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;

class DecodeLoadTest {
    @Test
    void twentyThousandChunksSurviveARestart() throws Exception {
        byte[] payload = Files.readAllBytes(smallestFixture());
        Path root = Files.createTempDirectory("jafra-decode-load");
        Path v2 = root.resolve("v2");
        int pods = 25;
        int rotations = 4;
        int chunksPerRecording = 200;
        int expected = pods * rotations * chunksPerRecording;
        ChunkStore store = new ChunkStore(root);
        store.recover();
        IngestCatalog catalog = new IngestCatalog(v2);
        try {
            catalog.setLanes(4);
            ChunkDecodeService service = new ChunkDecodeService(store, catalog, v2);
            service.setPauseBacklog(64);
            long base = 1_700_000_000_000_000_000L;
            long step = 5_000_000_000L;
            for (int pod = 0; pod < pods; pod++) {
                for (int rotation = 0; rotation < rotations; rotation++) {
                    for (int index = 0; index < chunksPerRecording; index++) {
                        int n = ((pod * rotations) + rotation) * chunksPerRecording + index;
                        long start = base + (rotation * chunksPerRecording + index) * step;
                        String name = "pod-" + pod;
                        String recording = name + "-profile-" + rotation + ".jfr";
                        ChunkMetadata metadata = new ChunkMetadata("chunk-" + n, recording, "cluster", "ns", "uid-" + name, name, "app",
                                recording, index, payload.length, "sum", start, step);
                        ChunkStore.IncomingWrite incoming = store.beginWrite(metadata.chunkId());
                        incoming.write(payload);
                        store.commit(incoming, metadata);
                        catalog.enqueue(metadata);
                    }
                }
            }
            AtomicBoolean run = new AtomicBoolean(true);
            AtomicBoolean roll = new AtomicBoolean(true);
            AtomicReference<ChunkDecodeService> current = new AtomicReference<>(service);
            AtomicReference<String> stall = new AtomicReference<>();
            Thread[] lanes = new Thread[4];
            Thread rollups = rollupThread(current, roll);
            long started = System.nanoTime();
            startLanes(service, run, lanes, stall);
            rollups.start();
            boolean restarted = false;
            long deadline = started + Duration.ofMinutes(20).toNanos();
            while (catalog.okCount() < expected) {
                assertTrue(System.nanoTime() < deadline, "still at " + catalog.okCount() + " of " + expected);
                assertNull(stall.get(), stall.get());
                if (!restarted && catalog.okCount() >= expected / 2) {
                    restarted = true;
                    run.set(false);
                    roll.set(false);
                    join(lanes, rollups);
                    catalog.close();
                    store.recover();
                    catalog = new IngestCatalog(v2);
                    service = new ChunkDecodeService(store, catalog, v2);
                    service.recover();
                    service.configureLanes(4);
                    service.setPauseBacklog(64);
                    current.set(service);
                    run.set(true);
                    roll.set(true);
                    startLanes(service, run, lanes, stall);
                    rollups = rollupThread(current, roll);
                    rollups.start();
                }
                Thread.sleep(20L);
            }
            long elapsed = System.nanoTime() - started;
            run.set(false);
            roll.set(false);
            join(lanes, rollups);
            assertNull(stall.get(), stall.get());
            assertEquals(expected, store.durableChunkCount());
            assertEquals(expected, catalog.okCount());
            assertEquals(pods, catalog.instances().size());
            long lastEnd = base + (rotations * chunksPerRecording - 1L) * step + step;
            for (IngestCatalog.ListedInstance instance : catalog.instances()) {
                assertTrue(instance.lastTNs() >= lastEnd, instance.pod() + " ended at " + instance.lastTNs());
            }
            System.out.println("LOAD chunks=" + expected + " seconds=" + (elapsed / 1_000_000_000.0));
        } finally {
            catalog.close();
        }
    }

    private static void startLanes(ChunkDecodeService service, AtomicBoolean run, Thread[] lanes, AtomicReference<String> stall) {
        for (int lane = 0; lane < lanes.length; lane++) {
            int index = lane;
            lanes[lane] = new Thread(() -> {
                long idleSince = 0L;
                while (run.get()) {
                    if (service.decodeNext(index, lanes.length, Instant.now())) {
                        idleSince = 0L;
                        continue;
                    }
                    long now = System.nanoTime();
                    if (idleSince == 0L) {
                        idleSince = now;
                    } else if (now - idleSince > Duration.ofSeconds(5).toNanos() && service.laneReady(index, Instant.now())) {
                        stall.set("lane " + index + " idle with ready work");
                        run.set(false);
                        return;
                    }
                    try {
                        Thread.sleep(5L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "load-lane-" + lane);
            lanes[lane].start();
        }
    }

    private static Thread rollupThread(AtomicReference<ChunkDecodeService> current, AtomicBoolean roll) {
        return new Thread(() -> {
            while (roll.get()) {
                current.get().drainRollups();
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "load-rollup");
    }

    private static void join(Thread[] lanes, Thread rollups) throws InterruptedException {
        for (Thread lane : lanes) {
            if (lane != null) {
                lane.join(10_000L);
            }
        }
        if (rollups != null) {
            rollups.join(10_000L);
        }
    }

    private static Path smallestFixture() throws Exception {
        Path fixtures = Path.of("src/test/resources/jfr-fixtures/chunks");
        try (var files = Files.list(fixtures)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".jfr"))
                    .min(Comparator.comparingLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (Exception error) {
                            return Long.MAX_VALUE;
                        }
                    }))
                    .orElseThrow();
        }
    }
}

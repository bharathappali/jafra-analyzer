package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;

class DecodeLoadTest {
    @Test
    void fourLanesDecodeTwentyFivePodsWithRotations() throws Exception {
        byte[] payload = Files.readAllBytes(smallestFixture());
        Path root = Files.createTempDirectory("jafra-decode-load");
        Path v2 = root.resolve("v2");
        int pods = 25;
        int rotations = 3;
        int chunksPerRecording = 40;
        int expected = pods * rotations * chunksPerRecording;
        try (IngestCatalog catalog = new IngestCatalog(v2)) {
            ChunkStore store = new ChunkStore(root);
            store.recover();
            catalog.setLanes(4);
            ChunkDecodeService service = new ChunkDecodeService(store, catalog, v2);
            long base = 1_700_000_000_000_000_000L;
            long step = 5_000_000_000L;
            for (int pod = 0; pod < pods; pod++) {
                for (int rotation = 0; rotation < rotations; rotation++) {
                    for (int index = 0; index < chunksPerRecording; index++) {
                        int n = ((pod * rotations) + rotation) * chunksPerRecording + index;
                        long start = base + (rotation * chunksPerRecording + index) * step;
                        String name = "pod-" + pod;
                        String recording = name + "-profile-" + rotation + ".jfr";
                        ChunkMetadata metadata = new ChunkMetadata("chunk-" + n, recording, "cluster", "ns",
                                "uid-" + name, name, "app", recording, index, payload.length, "sum", start, step);
                        ChunkStore.IncomingWrite incoming = store.beginWrite(metadata.chunkId());
                        incoming.write(payload);
                        store.commit(incoming, metadata);
                        catalog.enqueue(metadata);
                    }
                }
            }
            AtomicBoolean run = new AtomicBoolean(true);
            Thread[] lanes = new Thread[4];
            long started = System.nanoTime();
            for (int lane = 0; lane < lanes.length; lane++) {
                int index = lane;
                lanes[lane] = new Thread(() -> {
                    while (run.get()) {
                        if (!service.decodeNext(index, 4, Instant.now())) {
                            try {
                                Thread.sleep(5L);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }, "load-lane-" + lane);
                lanes[lane].start();
            }
            long deadline = started + Duration.ofMinutes(3).toNanos();
            while (catalog.okCount() < expected && System.nanoTime() < deadline) {
                Thread.sleep(20L);
            }
            long elapsed = System.nanoTime() - started;
            run.set(false);
            for (Thread lane : lanes) {
                lane.join(5_000L);
            }
            assertEquals(expected, store.durableChunkCount());
            assertEquals(expected, catalog.okCount());
            assertEquals(pods, catalog.instances().size());
            long lastEnd = base + (rotations * chunksPerRecording - 1) * step + step;
            for (IngestCatalog.ListedInstance instance : catalog.instances()) {
                assertTrue(instance.lastTNs() >= lastEnd, instance.pod() + " ended at " + instance.lastTNs());
            }
            assertTrue(elapsed < Duration.ofMinutes(3).toNanos(), "decode took " + Duration.ofNanos(elapsed));
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

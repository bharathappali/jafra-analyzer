package io.jafra.analyzer.v2.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.incidents.support.FullGcClassifier;
import io.jafra.analyzer.v2.rollup.ChunkFacts;
import io.jafra.analyzer.v2.rollup.ChunkReader;
import io.jafra.analyzer.v2.rollup.Frame;
import io.jafra.analyzer.v2.rollup.Seen;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.jafra.analyzer.v2.rollup.extract.Extractors;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

class FullGcClassifierTest {
    @Test
    void namesClassifyFullCollectorsAndIgnoreConcurrentCycles() {
        assertEquals(0d, count(gc("G1Young", 1), gc("G1Old", 2), oldCycle(2)));
        assertEquals(1d, count(gc("SerialOld", 3), oldCycle(3)));
        assertEquals(1d, count(gc("ParallelOld", 4)));
        assertEquals(1d, count(gc("G1Full", 5), gc("G1Old", 6)));
        assertTrue(new FullGcClassifier(List.of("SerialOld")).isFull("SerialOld"));
        assertTrue(!new FullGcClassifier(List.of("SerialOld")).isFull("G1Full"));
    }

    @Test
    void recordedSystemGcCountsOnlyFullNames() throws Exception {
        Path dump = Files.createTempFile("jafra-full-gc", ".jfr");
        long startMs = System.currentTimeMillis() - 1_000L;
        try (Recording recording = new Recording()) {
            recording.enable("jdk.GarbageCollection");
            recording.enable("jdk.OldGarbageCollection");
            recording.enable("jdk.G1GarbageCollection");
            recording.start();
            byte[] pressure = new byte[4_000_000];
            if (pressure.length == 0) {
                return;
            }
            System.gc();
            recording.dump(dump);
        }
        try (RecordingFile file = new RecordingFile(dump)) {
            ChunkFacts facts = new ChunkReader(10d).read(file, startMs, startMs + 5_000L);
            WindowData out = new WindowData();
            out.dtSeconds = facts.dtSeconds;
            Extractors.extract(facts, out);
            long named = facts.ofType("jdk.GarbageCollection").stream()
                    .filter(event -> FullGcClassifier.defaults().isFull(event.text("name")))
                    .count();
            assertTrue(named >= 1, "expected a G1Full from System.gc()");
            assertEquals(named, out.value("gc.full.count").longValue());
            assertTrue(facts.ofType("jdk.GarbageCollection").stream().anyMatch(event -> "G1Full".equals(event.text("name"))));
        } finally {
            Files.deleteIfExists(dump);
        }
    }

    private static double count(Seen... events) {
        ChunkFacts facts = new ChunkFacts(
                List.of(events), 5d, 0L, 5_000L, 21, "21", 0L, "aggressive", "{}", Map.of(), 10d);
        WindowData out = new WindowData();
        out.dtSeconds = 5d;
        Extractors.extract(facts, out);
        Double value = out.value("gc.full.count");
        return value == null ? -1d : value;
    }

    private static Seen gc(String name, long id) {
        return new Seen(
                "jdk.GarbageCollection",
                0L,
                1L,
                "",
                Map.of("name", name, "gcId", id, "sumOfPauses", 1_000d, "cause", "Allocation Failure"),
                List.<Frame>of());
    }

    private static Seen oldCycle(long id) {
        return new Seen("jdk.OldGarbageCollection", 0L, 1L, "", Map.of("gcId", id), List.of());
    }
}

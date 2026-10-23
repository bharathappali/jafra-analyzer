package io.jafra.analyzer.v2.rollup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.rollup.extract.Extractors;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

class RollupIndexTest {
    @Test
    void indexedRollupMatchesTheLinearScan() throws Exception {
        Path dump = Files.createTempFile("jafra-rollup-index", ".jfr");
        long startMs = System.currentTimeMillis() - 1_000L;
        try (Recording recording = new Recording()) {
            recording.enable("jdk.CPULoad");
            recording.enable("jdk.ExecutionSample");
            recording.enable("jdk.ThreadPark");
            recording.start();
            Thread.sleep(20);
            recording.stop();
            recording.dump(dump);
        }
        List<Seen> events;
        try (RecordingFile file = new RecordingFile(dump)) {
            events = new ArrayList<>(new ChunkReader(10d).read(file, startMs, startMs + 5_000L).events);
        } finally {
            Files.deleteIfExists(dump);
        }
        events.add(span("jdk.MethodTrace", "app", 1_000L, 5_000L, Map.of("method", "app.Outer.handle")));
        events.add(span("jdk.MethodTrace", "app", 2_000L, 3_000L, Map.of("method", "app.Inner.handle")));
        events.add(span("profiler.WallClockSample", "app", 2_500L, 2_500L, Map.of("samples", 1d, "state", "STATE_RUNNABLE")));
        events.add(span("profiler.WallClockSample", "app", 1_200L, 1_200L, Map.of("samples", 1d, "state", "STATE_WAITING")));
        events.add(span("jdk.SocketRead", "app", 1_100L, 1_300L, Map.of("host", "db", "port", 5432d)));
        events.add(span("jdk.ThreadPark", "other", 2_400L, 2_600L, Map.of()));

        ChunkFacts facts = new ChunkFacts(events, 5d, startMs, startMs + 5_000L, 21, "21", startMs, "custom", "{}", Map.of(), 10d);
        WindowData out = new WindowData();
        out.dtSeconds = 5d;
        Extractors.extract(facts, out);

        Map<Long, String> expectedHandlers = new LinkedHashMap<>();
        for (Seen sample : events) {
            if (!"profiler.WallClockSample".equals(sample.type)) {
                continue;
            }
            Seen call = linearEnclosing(events, sample);
            expectedHandlers.put(sample.startMs, call == null ? "" : call.text("method"));
            assertEquals(call, facts.enclosing("jdk.MethodTrace", sample));
        }
        assertEquals("app.Inner.handle", expectedHandlers.get(2_500L));
        assertEquals("app.Outer.handle", expectedHandlers.get(1_200L));
        for (WindowData.SampleKey key : out.samples().keySet()) {
            if (!"wall".equals(key.kind())) {
                continue;
            }
            assertTrue(expectedHandlers.containsValue(key.handler()), key.handler());
        }
        assertTrue(facts.overlaps("jdk.SocketRead", events.stream()
                .filter(event -> event.startMs == 1_200L && "profiler.WallClockSample".equals(event.type))
                .findFirst()
                .orElseThrow()));
        assertEquals(linearOverlaps(events, "jdk.SocketRead", 1_200L), true);

        String slow = out.events().stream()
                .filter(event -> "endpoint.slow".equals(event.type()))
                .map(WindowData.StoredEvent::fieldsJson)
                .filter(fields -> fields.contains("app.Inner.handle"))
                .findFirst()
                .orElse("");
        assertTrue(slow.contains("\"running_pct\":100"), slow);
    }

    @Test
    @Tag("perf")
    void largeChunkRollsUpWithinFiveSeconds() {
        String[] types = new String[30];
        types[0] = "profiler.WallClockSample";
        types[1] = "jdk.MethodTrace";
        for (int i = 2; i < types.length; i++) {
            types[i] = "jdk.Filler" + i;
        }
        List<Seen> events = new ArrayList<>(500_000);
        for (int i = 0; i < 20_000; i++) {
            events.add(span(
                    "profiler.WallClockSample",
                    "t" + (i % 32),
                    i,
                    i,
                    Map.of("samples", 1d, "state", "STATE_RUNNABLE")));
        }
        for (int i = 0; i < 4_000; i++) {
            long start = i * 5L;
            events.add(span("jdk.MethodTrace", "t" + (i % 32), start, start + 2, Map.of("method", "app.Handler")));
        }
        for (int i = events.size(); i < 500_000; i++) {
            events.add(span(types[2 + (i % 28)], "t" + (i % 32), i, i, Map.of("n", (double) i)));
        }
        long started = System.nanoTime();
        ChunkFacts facts = new ChunkFacts(events, 5d, 0L, 5_000L, 21, "21", 0L, "custom", "{}", Map.of(), 10d);
        WindowData out = new WindowData();
        out.dtSeconds = 5d;
        Extractors.extract(facts, out);
        long millis = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(millis < 5_000L, "rollup took " + millis + " ms");
        assertTrue(out.samples().size() > 0);
    }

    private static Seen span(String type, String thread, long start, long end, Map<String, Object> values) {
        return new Seen(type, start, end, thread, values, List.of());
    }

    /** The scan ChunkFacts.ofType plus a forward enclosing loop used to do. */
    private static Seen linearEnclosing(List<Seen> events, Seen sample) {
        Seen found = null;
        for (Seen event : events) {
            if (!"jdk.MethodTrace".equals(event.type) || !event.thread.equals(sample.thread)) {
                continue;
            }
            if (sample.startMs >= event.startMs && sample.startMs <= event.endMs) {
                found = event;
            }
        }
        return found;
    }

    private static boolean linearOverlaps(List<Seen> events, String type, long at) {
        for (Seen event : events) {
            if (!type.equals(event.type)) {
                continue;
            }
            if (!event.thread.isBlank() && !"app".equals(event.thread)) {
                continue;
            }
            if (at >= event.startMs && at <= event.endMs) {
                return true;
            }
        }
        return false;
    }
}

package io.jafra.analyzer.v2.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.rollup.Frame;
import io.jafra.analyzer.v2.store.V2Store.EventRow;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;
import io.jafra.analyzer.v2.store.V2Store.SampleRow;

class LongPauseDetectorTest {
    private static final double GIB = 1024d * 1024d * 1024d;
    private final HeapSizeAdvice advice = new HeapSizeAdvice(90);
    private final LongPauseDetector detector = new LongPauseDetector(200, 1000, 85, advice);

    @Test
    void pauseBandsScoreFromTheLongestEvent() {
        WindowVerdict under = evaluate(pause(199_000, "{}"));
        assertEquals(State.OK, under.state());
        assertEquals(199d, under.value(), 1e-6);

        WindowVerdict warning = evaluate(pause(200_000, "{}"));
        assertEquals(State.WARNING, warning.state());
        assertEquals(60d, IncidentLifecycle.score(warning.value(), warning.band()), 1e-6);

        WindowVerdict critical = evaluate(pause(1_000_000, "{}"));
        assertEquals(State.CRITICAL, critical.state());
        assertEquals(90d, IncidentLifecycle.score(critical.value(), critical.band()), 1e-6);

        WindowVerdict top = evaluate(pause(2_000_000, "{}"));
        assertEquals(100d, IncidentLifecycle.score(top.value(), top.band()), 1e-6);

        assertEquals(State.OK, evaluate(new Scene("{}")).state());
        assertEquals(0d, evaluate(new Scene("{}")).value(), 1e-6);
    }

    @Test
    void maxGcPauseMillisRaisesTheBandAndPauseTargetDoesNot() {
        Scene flagged = pause(250_000, "{\"gc\":{\"pause_target_ms\":500},\"jvm\":{\"arguments\":\"-XX:MaxGCPauseMillis=300\"}}");
        WindowVerdict raised = evaluate(flagged);
        assertEquals(300d, raised.band().warning(), 1e-6);
        assertEquals(State.OK, raised.state());
        assertTrue(raised.reason().contains("band 300 ms (-XX:MaxGCPauseMillis=300 in jvmArguments)"));
        assertFalse(raised.reason().contains("500"));

        Scene targetOnly = pause(250_000, "{\"gc\":{\"pause_target_ms\":500},\"jvm\":{\"arguments\":\"-XX:+UseG1GC\"}}");
        WindowVerdict floor = evaluate(targetOnly);
        assertEquals(State.WARNING, floor.state());
        assertEquals(200d, floor.band().warning(), 1e-6);
        assertTrue(floor.reason().contains("band 200 ms (floor, proposal, pending KUSMA)"));
        assertFalse(floor.reason().contains("500"));
    }

    @Test
    void p99TermNeedsSixtyWindowsAndFiftyGcs() {
        Scene applied = pause(500_000, "{}");
        applied.baseline = new Baseline(1_000d, 0d, 60, 50, 204_000d, Set.of());
        WindowVerdict qualified = evaluate(applied);
        assertEquals(612d, qualified.band().warning(), 1e-6);
        assertEquals(State.OK, qualified.state());
        assertTrue(qualified.reason().contains(
                "band 612 ms (3 × baseline p99 204 ms over 60 windows, 50 GCs)"));
        assertFalse(qualified.flags().contains("unproven_baseline"));

        Scene shortWindows = pause(500_000, "{}");
        shortWindows.baseline = new Baseline(1_000d, 0d, 59, 80, 204_000d, Set.of());
        WindowVerdict windows = evaluate(shortWindows);
        assertEquals(State.WARNING, windows.state());
        assertEquals(200d, windows.band().warning(), 1e-6);
        assertTrue(windows.flags().contains("unproven_baseline"));

        Scene shortGcs = pause(500_000, "{}");
        shortGcs.baseline = new Baseline(1_000d, 0d, 60, 49, 204_000d, Set.of());
        WindowVerdict gcs = evaluate(shortGcs);
        assertEquals(200d, gcs.band().warning(), 1e-6);
        assertTrue(gcs.flags().contains("unproven_baseline"));
    }

    @Test
    void longestPauseFillsHeadlineAndTodos() {
        Scene scene = new Scene(
                "{\"jvm\":{\"arguments\":\"-XX:+UseG1GC\"},\"gc\":{\"young\":\"G1 Young Generation\",\"old\":\"G1 Old Generation\",\"pause_target_ms\":200}}");
        scene.events.add(pauseEvent(100_000, "Serial", "Metadata GC Threshold", 1, 0, 0));
        scene.events.add(pauseEvent(400_000, "G1Young", "G1 Evacuation Pause", 4, 1.5 * GIB, 0.5 * GIB));
        scene.internal.add(phase(1, "Should Not Appear", 900_000));
        scene.internal.add(phase(4, "Ext Root Scanning", 80_000));
        scene.totals.put("heap.max.bytes", total("heap.max.bytes", 2 * GIB));
        scene.totals.put("heap.occupancy.pct", span("heap.occupancy.pct", 90, 99, 10));

        WindowVerdict verdict = evaluate(scene);
        assertEquals(State.WARNING, verdict.state());
        assertEquals("site", verdict.who().kind());
        assertEquals("G1Young · G1 Evacuation Pause · Ext Root Scanning", verdict.who().name());
        assertFalse(verdict.who().estimate());
        assertEquals(
                "G1Young pause 400 ms (G1 Evacuation Pause, phase Ext Root Scanning) vs the 200 ms band",
                verdict.headline());
        assertFalse(verdict.headline().contains("Serial"));
        assertFalse(verdict.headline().contains("Metadata"));
        assertEquals(
                "Requests stalled for up to 400 ms while the JVM paused for GC.",
                verdict.felt());
        assertEquals(3, verdict.todo().size());
        assertEquals(
                "collector G1 Young Generation/G1 Old Generation; no -XX:MaxGCPauseMillis in jvmArguments",
                verdict.todo().get(0));
        assertEquals(
                "heap max is 2.0 GiB (no -Xmx); heap was 1.5 GiB -> 512.0 MiB around the pause",
                verdict.todo().get(1));
        assertEquals(
                "raise -XX:MaxRAMPercentage from 25% (JDK default) (no pod memory limit set, heap max now 2.0 GiB)",
                verdict.todo().get(2));
        for (String line : verdict.todo()) {
            assertFalse(line.contains("<"), line);
        }
        assertTrue(verdict.reason().contains("band 200 ms (floor, proposal, pending KUSMA)"));
        assertTrue(verdict.reason().contains("no allocation samples"));
        assertTrue(verdict.reason().contains("flags read from jvmArguments; JAVA_TOOL_OPTIONS unverified"));
        assertFalse(verdict.reason().contains("no GCPhasePauseLevel1"));
        assertFalse(verdict.reason().contains("MaxGCPauseMillis"));
        assertTrue(verdict.sourceEvents().contains("jdk.GarbageCollection"));
        assertTrue(verdict.sourceEvents().contains("jdk.GCHeapSummary"));
        assertTrue(verdict.sourceEvents().contains("jdk.GCPhasePauseLevel1"));
        assertTrue(verdict.where().isEmpty());
        assertFalse(verdict.flags().contains("estimate"));
    }

    @Test
    void whereUsesFrameServiceAppCaller() {
        Scene scene = pause(300_000, "{\"jvm\":{\"arguments\":\"-XX:MaxGCPauseMillis=200\"},\"gc\":{\"young\":\"G1\",\"old\":\"G1\"}}");
        scene.events.clear();
        scene.events.add(pauseEvent(300_000, "G1Young", "Allocation Failure", 2, GIB, GIB));
        scene.samples.add(new SampleRow(0, "alloc", 7L, "main", "", "", 1, 4));
        scene.stacks.put(7L, List.of(
                new Frame("unknown_native", "native", "Native", 0),
                new Frame("java.util.HashMap.resize", "jdk", "Interpreted", 0),
                new Frame("com.acme.Cache.put", "app", "Interpreted", 12)));
        scene.totals.put("heap.occupancy.pct", span("heap.occupancy.pct", 10, 10, 10));

        WindowVerdict verdict = evaluate(scene);
        assertEquals(1, verdict.where().size());
        assertEquals("com.acme.Cache.put", verdict.where().get(0).frame());
        assertEquals(100d, verdict.where().get(0).sharePct(), 1e-6);
        assertEquals(AllocSites.SOURCE_EVENTS, verdict.where().get(0).sourceEvents());
        assertTrue(verdict.flags().contains("estimate"));
        assertTrue(verdict.sourceEvents().containsAll(AllocSites.SOURCE_EVENTS));
        assertEquals(
                "reduce allocation at com.acme.Cache.put (100% of sampled bytes)",
                verdict.todo().get(2));
        assertFalse(verdict.reason().contains("no allocation samples"));
    }

    @Test
    void systemGcNamesTheTopFrame() {
        Scene scene = pause(300_000, "{\"gc\":{\"young\":\"G1\",\"old\":\"G1\"}}");
        scene.events.clear();
        scene.events.add(pauseEvent(300_000, "G1Full", "System.gc()", 3, GIB, 0.5 * GIB));
        scene.events.add(new EventRow("gc.system_gc", 0, 1_000L, "main", "com.acme.Caches.clear", "{}"));
        WindowVerdict verdict = evaluate(scene);
        assertEquals(
                "remove the System.gc() call at com.acme.Caches.clear or add -XX:+DisableExplicitGC",
                verdict.todo().get(2));
        assertTrue(verdict.headline().contains("System.gc()"));
    }

    @Test
    void heapSizeAdviceCases() {
        RollupView limits = totals(
                total("heap.max.bytes", GIB),
                total("container.limit.bytes", 2 * GIB));
        assertEquals(
                "raise -XX:MaxRAMPercentage from 25% (JDK default) (pod memory limit 2.0 GiB, heap max now 1.0 GiB)",
                advice.todo(settings("{}"), limits));
        assertEquals(
                "raise -XX:MaxRAMPercentage from 60% (pod memory limit 2.0 GiB, heap max now 1.0 GiB)",
                advice.todo(settings("{\"jvm\":{\"arguments\":\"-XX:MaxRAMPercentage=60\"}}"), limits));

        RollupView empty = totals();
        assertEquals(
                "raise -Xmx from 2.0 GiB",
                advice.todo(settings("{\"jvm\":{\"arguments\":\"-Xmx2g\"},\"heap\":{\"max\":" + (long) (2 * GIB) + "}}"), empty));
        assertEquals(
                "raise -Xmx from 2.0 GiB",
                advice.todo(
                        settings("{\"jvm\":{\"arguments\":\"-XX:MaxHeapSize=2g\"},\"heap\":{\"max\":" + (long) (2 * GIB) + "}}"),
                        empty));

        RollupView hot = totals(
                total("container.limit.bytes", 4 * GIB),
                span("container.usage.pct_limit", 1, 90, 10));
        assertEquals(
                "raise the pod memory limit 4.0 GiB first: metaspace, threads and direct buffers need room outside the heap",
                advice.todo(settings("{\"jvm\":{\"arguments\":\"-Xmx1g\"}}"), hot));

        assertEquals(
                "raise -XX:MaxRAMPercentage from 25% (JDK default) (no pod memory limit set, heap max now 512.0 MiB)",
                advice.todo(settings("{\"heap\":{\"max\":" + (512 * 1024 * 1024) + "}}"), empty));
    }

    private WindowVerdict evaluate(Scene scene) {
        return detector.evaluate(scene.input());
    }

    private static Scene pause(long durationUs, String settings) {
        Scene scene = new Scene(settings);
        scene.events.add(pauseEvent(durationUs, "G1Young", "Allocation Failure", 1, GIB, GIB));
        return scene;
    }

    private static EventRow pauseEvent(
            long durationUs, String collector, String cause, long gcId, double before, double after) {
        String fields = "{\"collector\":\"" + collector + "\",\"cause\":\"" + cause + "\",\"gc_id\":" + gcId
                + ",\"heap_before\":" + before + ",\"heap_after\":" + after + "}";
        return new EventRow("gc.pause", 0, durationUs, "", null, fields);
    }

    private static EventRow phase(long gcId, String name, long durationUs) {
        return new EventRow("internal.gc.phase_l1", 0, durationUs, "", null, "{\"gc_id\":" + gcId + ",\"phase\":\"" + name + "\"}");
    }

    private static JvmSettings settings(String json) {
        return new JvmSettings(jvm(json));
    }

    private static JvmRow jvm(String json) {
        return new JvmRow(1, "c", "ns", "pod", "uid", "app", 1, 21, "21", "low", 0, 5_000, 1, json);
    }

    private static RollupRow total(String metric, double value) {
        return new RollupRow(1, metric, "", "", 0, value, value, value, 1, value);
    }

    private static RollupRow span(String metric, double min, double max, double avg) {
        return new RollupRow(1, metric, "", "", 0, min, max, avg, 1, avg);
    }

    private static RollupView totals(RollupRow... rows) {
        Map<String, RollupRow> map = new LinkedHashMap<>();
        for (RollupRow row : rows) {
            map.put(row.metric(), row);
        }
        return new MapRollup(map, Map.of(), Map.of());
    }

    private static Baseline proven() {
        return new Baseline(1_000d, 0d, 60, 50, 10_000d, Set.of());
    }

    private static final class Scene {
        private final String settings;
        private final Map<String, RollupRow> totals = new LinkedHashMap<>();
        private final List<EventRow> events = new ArrayList<>();
        private final List<EventRow> internal = new ArrayList<>();
        private final List<SampleRow> samples = new ArrayList<>();
        private final Map<Long, List<Frame>> stacks = new LinkedHashMap<>();
        private Baseline baseline = proven();

        Scene(String settings) {
            this.settings = settings;
        }

        WindowInput input() {
            Baseline captured = baseline;
            Baselines baselines = (name, key, group, stat) -> {
                if (!"gc.pause.us".equals(name) || !"".equals(key) || !"gc".equals(group) || !"max".equals(stat)) {
                    throw new AssertionError(name + "/" + key + "/" + group + "/" + stat);
                }
                return captured;
            };
            return new WindowInput(
                    jvm(settings),
                    0,
                    5_000,
                    new MapRollup(totals, Map.of(), Map.of()),
                    new FixedSamples(samples, stacks),
                    new FixedEvents(events, internal),
                    baselines,
                    new JvmSettings(jvm(settings)));
        }
    }

    private static final class MapRollup implements RollupView {
        private final Map<String, RollupRow> totals;
        private final Map<String, RollupRow> before;
        private final Map<String, List<RollupRow>> breakdowns;

        MapRollup(Map<String, RollupRow> totals, Map<String, RollupRow> before, Map<String, List<RollupRow>> breakdowns) {
            this.totals = totals;
            this.before = before;
            this.breakdowns = breakdowns;
        }

        @Override
        public Optional<RollupRow> total(String metric) {
            return Optional.ofNullable(totals.get(metric));
        }

        @Override
        public List<RollupRow> breakdown(String metric, String by) {
            return breakdowns.getOrDefault(metric + "\0" + by, List.of());
        }

        @Override
        public Map<Integer, Long> hist(String metric, String key) {
            return Map.of();
        }

        @Override
        public Optional<RollupRow> lastBefore(String metric) {
            return Optional.ofNullable(before.get(metric));
        }
    }

    private static final class FixedSamples implements SampleView {
        private final List<SampleRow> samples;
        private final Map<Long, List<Frame>> stacks;

        FixedSamples(List<SampleRow> samples, Map<Long, List<Frame>> stacks) {
            this.samples = samples;
            this.stacks = stacks;
        }

        @Override
        public List<SampleRow> rows(String kind) {
            return recent(kind, 1);
        }

        @Override
        public List<SampleRow> recent(String kind, int windows) {
            if (!"alloc".equals(kind)) {
                return List.of();
            }
            return samples;
        }

        @Override
        public List<Frame> stack(long stackId) {
            return stacks.getOrDefault(stackId, List.of());
        }
    }

    private static final class FixedEvents implements EventView {
        private final List<EventRow> events;
        private final List<EventRow> internal;

        FixedEvents(List<EventRow> events, List<EventRow> internal) {
            this.events = events;
            this.internal = internal;
        }

        @Override
        public List<EventRow> of(String type) {
            List<EventRow> found = new ArrayList<>();
            for (EventRow event : events) {
                if (type.equals(event.type())) {
                    found.add(event);
                }
            }
            return found;
        }

        @Override
        public List<EventRow> internal(String name) {
            return "internal.gc.phase_l1".equals(name) ? internal : List.of();
        }
    }
}

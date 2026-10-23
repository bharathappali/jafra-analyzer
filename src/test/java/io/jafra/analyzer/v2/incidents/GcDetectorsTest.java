package io.jafra.analyzer.v2.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.store.V2Store.EventRow;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

class GcDetectorsTest {
    private final HeapSizeAdvice advice = new HeapSizeAdvice(90);

    @Test
    void gcOverheadUsesPauseAloneUnlessGcCpuIsMeasured() {
        GcOverheadDetector detector = new GcOverheadDetector(10, 25, advice);
        WindowVerdict zgc = detector.evaluate(input(
                "{\"gc\":{\"young\":\"ZGC\",\"old\":\"ZGC\"},\"jvm\":{\"arguments\":\"\"},\"declared_event_types\":[\"jdk.GCCPUTime\"]}",
                Map.of("gc.pause.time_pct", 12d, "gc.cpu.pct", 80d, "cpu.container.pct_limit", 50d),
                List.of()));
        assertEquals(State.WARNING, zgc.state());
        assertEquals(12d, zgc.value(), 1e-6);
        assertTrue(zgc.reason().contains("GC CPU not measured"));

        WindowVerdict g1 = detector.evaluate(input(
                "{\"gc\":{\"young\":\"G1New\",\"old\":\"G1Old\"},\"declared_event_types\":[\"jdk.GarbageCollection\"]}",
                Map.of("gc.pause.time_pct", 8d, "gc.cpu.pct", 40d, "cpu.container.pct_limit", 80d),
                List.of()));
        assertEquals(State.OK, g1.state());
        assertEquals(8d, g1.value(), 1e-6);

        WindowVerdict both = detector.evaluate(input(
                "{\"gc\":{\"young\":\"G1New\",\"old\":\"G1Old\"},\"declared_event_types\":[\"jdk.GCCPUTime\"]}",
                Map.of("gc.pause.time_pct", 8d, "gc.cpu.pct", 40d, "cpu.container.pct_limit", 80d),
                List.of()));
        assertEquals(32d, both.value(), 1e-6);
        assertEquals(State.CRITICAL, both.state());
    }

    @Test
    void fullGcIgnoresG1OldAndCountsG1Full() {
        FullGcDetector detector = new FullGcDetector(3, 12, null, advice);
        String g1 = "{\"gc\":{\"young\":\"G1New\",\"old\":\"G1Old\"},\"jvm\":{\"arguments\":\"\"}}";
        WindowVerdict concurrent = detector.evaluate(input(g1, Map.of(), List.of(
                pause("G1Old", "G1 Evacuation Pause", 1_000))));
        assertEquals(State.OK, concurrent.state());

        WindowVerdict full = detector.evaluate(input(g1, Map.of(), List.of(
                pause("G1Full", "System.gc()", 2_000))));
        assertEquals(State.WARNING, full.state());
        assertEquals("System.gc()", full.who().name());

        WindowVerdict three = detector.evaluate(input(g1, Map.of(), List.of(
                pause("G1Full", "Allocation Failure", 1),
                pause("G1Full", "Allocation Failure", 1),
                pause("G1Full", "Allocation Failure", 1))));
        assertEquals(State.CRITICAL, three.state());
        assertEquals(3d, three.value(), 1e-6);
    }

    @Test
    void evacuationFailureIsCriticalAndSystemGcIsWarningOnly() {
        EvacPromotionFailureDetector evac = new EvacPromotionFailureDetector(advice);
        WindowVerdict failed = evac.evaluate(input(
                "{\"gc\":{\"young\":\"G1New\",\"old\":\"G1Old\"},\"jvm\":{\"arguments\":\"-XX:InitiatingHeapOccupancyPercent=45\"}}",
                Map.of(),
                List.of(
                        pause("G1New", "G1 Evacuation Pause", 1_000, 7),
                        new EventRow("gc.evac_failed", 0, 1L, "main", null,
                                "{\"object_count\":4,\"total_size_bytes\":128,\"gc_id\":7}"))));
        assertEquals(State.CRITICAL, failed.state());
        assertTrue(failed.todo().get(1).contains("45"));

        SystemGcCallsDetector calls = new SystemGcCallsDetector();
        WindowVerdict called = calls.evaluate(input(
                "{\"jvm\":{\"arguments\":\"\"}}",
                Map.of(),
                List.of(new EventRow("gc.system_gc", 0, 1L, "main", "com.app.Main.gc", "{}"))));
        assertEquals(State.WARNING, called.state());
        WindowVerdict disabled = calls.evaluate(input(
                "{\"jvm\":{\"arguments\":\"-XX:+DisableExplicitGC\"}}",
                Map.of(),
                List.of(new EventRow("gc.system_gc", 0, 1L, "main", "com.app.Main.gc", "{}"))));
        assertEquals(State.OK, disabled.state());
        assertTrue(disabled.reason().contains("explicit GC disabled"));
    }

    @Test
    void zgcStallPercentUsesTheWindow() {
        ZgcAllocStallDetector detector = new ZgcAllocStallDetector(1, advice);
        WindowVerdict other = detector.evaluate(input(
                "{\"gc\":{\"young\":\"G1New\",\"old\":\"G1Old\"}}",
                Map.of("gc.zgc.stall.us", 1_000_000d),
                List.of()));
        assertEquals(State.OK, other.state());
        assertTrue(other.reason().contains("not ZGC"));

        WindowVerdict stall = detector.evaluate(input(
                "{\"gc\":{\"young\":\"ZGC\",\"old\":\"ZGC\"}}",
                Map.of("gc.zgc.stall.us", 100_000d),
                List.of()));
        assertEquals(State.CRITICAL, stall.state());
    }

    @Test
    void gcGapsNameTheCauseFrameAndStallThread() {
        GcOverheadDetector overhead = new GcOverheadDetector(10, 25, advice);
        WindowVerdict critical = overhead.evaluate(input(
                "{\"gc\":{\"young\":\"G1New\",\"old\":\"G1Old\"},\"declared_event_types\":[\"jdk.GCCPUTime\"]}",
                Map.of("gc.pause.time_pct", 30d),
                List.of()));
        assertTrue(critical.reason().contains("band 25%"));
        assertTrue(critical.who().name().contains("G1New/G1Old"));
        assertTrue(critical.sourceEvents().contains("jdk.GarbageCollection"));

        FullGcDetector full = new FullGcDetector(3, 12, null, advice);
        WindowVerdict system = full.evaluate(input(
                "{\"gc\":{\"young\":\"G1New\",\"old\":\"G1Old\"},\"jvm\":{\"arguments\":\"\"}}",
                Map.of(),
                List.of(
                        pause("G1Full", "System.gc()", 2_000),
                        new EventRow("gc.system_gc", 0, 1L, "main", "com.app.Main.gc", "{}"))));
        assertEquals("com.app.Main.gc", system.where().get(0).frame());
        assertTrue(system.todo().get(0).contains("com.app.Main.gc"));

        WindowVerdict alloc = full.evaluate(input(
                "{\"gc\":{\"young\":\"ParallelScavenge\",\"old\":\"ParallelOld\"},\"jvm\":{\"arguments\":\"-Xmx1g\"}}",
                Map.of(),
                List.of(pause("ParallelOld", "Allocation Failure", 2_000))));
        assertTrue(alloc.todo().stream().noneMatch(line -> line.contains("the top alloc frame")));

        FullGcDetector failing = new FullGcDetector(3, 12, new io.jafra.analyzer.v2.incidents.support.DetectorHistory(null, null) {
            @Override
            public double sum(int jvmId, String metric, String key, String stat, int windows, long throughMs) {
                throw new IllegalStateException("history down");
            }
        }, advice);
        WindowInput history = input(
                "{\"gc\":{\"young\":\"G1New\",\"old\":\"G1Old\"}}",
                Map.of(),
                List.of(pause("G1Full", "Allocation Failure", 1)));
        history = new WindowInput(
                new JvmRow(4, "c", "ns", "pod", "uid", "app", 1, 21, "21", "low", 0, 5_000, 1, history.settings().raw().toString()),
                history.fromMs(), history.toMs(), history.window(), history.samples(), history.events(), history.baselines(),
                history.settings());
        WindowVerdict unknown = failing.evaluate(history);
        assertEquals(State.UNKNOWN, unknown.state());
        assertTrue(unknown.reason().contains("history down"));

        EvacPromotionFailureDetector evac = new EvacPromotionFailureDetector(advice);
        WindowVerdict serial = evac.evaluate(input(
                "{\"jvm\":{\"arguments\":\"-Xmx1g\"}}",
                Map.of(),
                List.of(
                        pause("SerialOld", "Allocation Failure", 1, 3),
                        new EventRow("gc.promotion_failed", 0, 1L, "main", null,
                                "{\"object_count\":1,\"total_size_bytes\":8,\"gc_id\":3}"))));
        assertTrue(serial.who().name().startsWith("SerialOld"));
        assertTrue(serial.todo().stream().anyMatch(line -> line.startsWith("reduce allocation at ")));

        ZgcAllocStallDetector stalls = new ZgcAllocStallDetector(1, advice);
        WindowVerdict notZ = stalls.evaluate(input("{\"gc\":{\"young\":\"Shenandoah\",\"old\":\"Shenandoah\"}}", Map.of("gc.zgc.stall.us", 1d), List.of()));
        assertEquals(State.OK, notZ.state());
        WindowVerdict generational = stalls.evaluate(input(
                "{\"gc\":{\"young\":\"ZGC Minor\",\"old\":\"ZGC Major\"}}",
                Map.of("gc.zgc.stall.us", 10_000d),
                List.of(
                        new EventRow("internal.zgc.stall", 1, 4_000L, "slow", "app.Slow.alloc", "{\"top_frame\":\"app.Slow.alloc\"}"),
                        new EventRow("internal.zgc.stall", 2, 1_000L, "fast", "app.Fast.alloc", "{\"top_frame\":\"app.Fast.alloc\"}"),
                        new EventRow("internal.zgc.stall", 3, 1_000L, "slow", "app.Slow.alloc", "{\"top_frame\":\"app.Slow.alloc\"}"))));
        assertEquals("slow", generational.who().name());
        assertEquals(100d * 5_000d / 6_000d, generational.who().sharePct(), 1e-6);
        assertEquals("app.Slow.alloc", generational.where().get(0).frame());
        assertTrue(generational.todo().get(1).contains("app.Slow.alloc"));
        assertTrue(!generational.todo().get(1).contains("top stall frame"));
    }

    private static EventRow pause(String collector, String cause, long durationUs) {
        return pause(collector, cause, durationUs, -1);
    }

    private static EventRow pause(String collector, String cause, long durationUs, long gcId) {
        String id = gcId < 0 ? "" : ",\"gc_id\":" + gcId;
        return new EventRow("gc.pause", 0, durationUs, "", null,
                "{\"collector\":\"" + collector + "\",\"cause\":\"" + cause + "\"" + id + "}");
    }

    private static WindowInput input(String settings, Map<String, Double> totals, List<EventRow> events) {
        JvmRow jvm = new JvmRow(0, "c", "ns", "pod", "uid", "app", 1, 21, "21", "low", 0, 5_000, 1, settings);
        return new WindowInput(jvm, 0, 5_000, new Totals(totals), new SampleView() {
            @Override
            public List<io.jafra.analyzer.v2.store.V2Store.SampleRow> rows(String kind) {
                return List.of();
            }

            @Override
            public List<io.jafra.analyzer.v2.store.V2Store.SampleRow> recent(String kind, int windows) {
                return List.of();
            }

            @Override
            public List<io.jafra.analyzer.v2.rollup.Frame> stack(long stackId) {
                return List.of();
            }
        }, new Events(events),
                (name, key, group, stat) -> new Baseline(0d, 0d, 0, 0, null, java.util.Set.of()), new JvmSettings(jvm));
    }

    private record Totals(Map<String, Double> values) implements RollupView {
        @Override
        public Optional<RollupRow> total(String metric) {
            Double value = values.get(metric);
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(new RollupRow(0, metric, "", "", 0, value, value, value, 1, value));
        }

        @Override
        public List<RollupRow> breakdown(String metric, String by) {
            return List.of();
        }

        @Override
        public Map<Integer, Long> hist(String metric, String key) {
            return Map.of();
        }

        @Override
        public Optional<RollupRow> lastBefore(String metric) {
            return Optional.empty();
        }
    }

    private record Events(List<EventRow> rows) implements EventView {
        @Override
        public List<EventRow> of(String type) {
            return rows.stream().filter(row -> type.equals(row.type())).toList();
        }

        @Override
        public List<EventRow> internal(String name) {
            return rows.stream().filter(row -> name.equals(row.type())).toList();
        }
    }
}

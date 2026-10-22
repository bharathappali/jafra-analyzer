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
                List.of(new EventRow("gc.evac_failed", 0, 1L, "main", null, "{\"object_count\":4,\"total_size_bytes\":128}"))));
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

    private static EventRow pause(String collector, String cause, long durationUs) {
        return new EventRow("gc.pause", 0, durationUs, "", null,
                "{\"collector\":\"" + collector + "\",\"cause\":\"" + cause + "\"}");
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
            return List.of();
        }
    }
}

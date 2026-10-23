package io.jafra.analyzer.v2.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.api.V2Times;
import io.jafra.analyzer.v2.catalogue.Catalogue;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.rollup.Frame;
import io.jafra.analyzer.v2.rollup.WindowCommitted;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.jafra.analyzer.v2.store.V2Store;
import io.jafra.analyzer.v2.store.V2Store.CommitResult;
import io.jafra.analyzer.v2.store.V2Store.EventRow;
import io.jafra.analyzer.v2.store.V2Store.IncidentRecord;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;
import io.jafra.analyzer.v2.store.V2Store.SampleRow;
import io.jafra.analyzer.v2.surface.SurfaceService;

class HeapNearFullDetectorTest {
    private final HeapSizeAdvice advice = new HeapSizeAdvice(90);
    private final HeapNearFullDetector detector = new HeapNearFullDetector(85, 95, advice);

    @Test
    void occupancyUsesMinAgainst85And95() {
        WindowVerdict under = detector.evaluate(input(span(84.9, 99, 99), Map.of(), null));
        assertEquals(State.OK, under.state());
        assertEquals(84.9, under.value(), 1e-6);
        assertNull(under.who());
        assertTrue(under.reason().contains("no allocation samples"));

        WindowVerdict warning = detector.evaluate(input(span(85, 99, 10), classBreakdown(), null));
        assertEquals(State.WARNING, warning.state());
        assertEquals(100d, warning.limit(), 1e-6);
        assertEquals("pct", warning.unit());
        assertEquals("Heap 85% full after GC vs the 85% band", warning.headline());
        assertEquals("The heap stayed 85% full even after GC, so GCs ran more often and freed less.", warning.felt());
        assertEquals("class", warning.who().kind());
        assertEquals("com.acme.Box", warning.who().name());
        assertEquals(80d, warning.who().sharePct(), 1e-6);
        assertTrue(warning.who().estimate());
        assertTrue(warning.flags().contains("estimate"));
        assertTrue(warning.sourceEvents().contains("jdk.GCHeapSummary"));
        assertTrue(warning.sourceEvents().contains("jdk.GCHeapConfiguration"));
        assertTrue(warning.sourceEvents().contains("jdk.ObjectAllocationInNewTLAB"));
        assertTrue(warning.reason().contains("band 85% / 95% (proposal, pending KUSMA)"));
        assertTrue(warning.reason().contains("flags read from jvmArguments; JAVA_TOOL_OPTIONS unverified"));

        WindowVerdict critical = detector.evaluate(input(span(95, 99, 10), Map.of(), null));
        assertEquals(State.CRITICAL, critical.state());
        assertEquals("Heap 95% full after GC vs the 95% band", critical.headline());
    }

    @Test
    void unknownBeforeTheFirstGc() {
        WindowVerdict verdict = detector.evaluate(input(null, Map.of(), null));
        assertEquals(State.UNKNOWN, verdict.state());
        assertEquals("no GC yet in this JVM", verdict.reason());
    }

    @Test
    void carryForwardHasNoDetectorMemory() {
        RollupRow prior = new RollupRow(1, "heap.occupancy.pct", "", "", 5_000, 96, 96, 96, 1, 96);
        WindowInput carried = input(null, Map.of(), prior);
        WindowVerdict first = detector.evaluate(carried);
        WindowVerdict second = new HeapNearFullDetector(85, 95, advice).evaluate(carried);
        assertEquals(State.CRITICAL, second.state());
        assertEquals(96d, second.value(), 1e-6);
        assertEquals(5_000L, second.measuredAt());
        assertEquals(first.measuredAt(), second.measuredAt());
        assertTrue(second.flags().contains("carried_forward"));
        assertTrue(second.reason().contains("carried forward from " + V2Times.text(5_000)));
    }

    @Test
    void newEngineCarriesForwardFromTheStore() throws Exception {
        Path root = Files.createTempDirectory("jafra-heap-carry");
        V2Store store = new V2Store(root);
        try {
            int jvmId = commit(store, 0, true).jvmId();
            commit(store, 5_000, false);
            WindowInput later = new WindowInput(
                    store.jvm(jvmId),
                    5_000,
                    10_000,
                    WindowViews.rollup(store, jvmId, 5_000),
                    WindowViews.samples(store, jvmId, 5_000, 10_000),
                    WindowViews.events(store, jvmId, 5_000, 10_000),
                    (name, key, group, stat) -> new Baseline(null, null, 0, 0, null, java.util.Set.of()),
                    new JvmSettings(store.jvm(jvmId)));
            WindowVerdict fresh = new HeapNearFullDetector(85, 95, advice).evaluate(later);
            assertEquals(96d, fresh.value(), 1e-6);
            assertEquals(0L, fresh.measuredAt());
            assertTrue(fresh.flags().contains("carried_forward"));

            Catalogue catalogue = Catalogue.load();
            BaselineService baselines = new BaselineService(store, catalogue);
            IncidentEngine engine = new IncidentEngine(
                    store,
                    List.of(new HeapNearFullDetector(85, 95, advice)),
                    null,
                    baselines,
                    new SurfaceService(store, catalogue, baselines, null, null, null));
            engine.onWindow(new WindowCommitted("w-0", jvmId, 0, 5_000, false));
            engine.onWindow(new WindowCommitted("w-5000", jvmId, 5_000, 10_000, false));
            IncidentRecord opened = store.incident("heap_near_full-0");
            assertEquals("open", opened.status());
            assertTrue(opened.flagsJson().contains("carried_forward"));
            assertTrue(opened.howBadJson().contains("\"measured_at\":\"1970-01-01T00:00:00.000Z\""));
        } finally {
            store.close();
        }
    }

    @Test
    void classComesFromEarlierWindowsWhenThisWindowHasNone() throws Exception {
        Path root = Files.createTempDirectory("jafra-heap-class");
        V2Store store = new V2Store(root);
        try {
            int jvmId = commitClasses(store, 0).jvmId();
            commit(store, 5_000, true);
            WindowVerdict verdict = new HeapNearFullDetector(85, 95, advice).evaluate(new WindowInput(
                    store.jvm(jvmId),
                    5_000,
                    10_000,
                    WindowViews.rollup(store, jvmId, 5_000),
                    WindowViews.samples(store, jvmId, 5_000, 10_000),
                    WindowViews.events(store, jvmId, 5_000, 10_000),
                    (name, key, group, stat) -> new Baseline(null, null, 0, 0, null, java.util.Set.of()),
                    new JvmSettings(store.jvm(jvmId))));
            assertEquals("class", verdict.who().kind());
            assertEquals("com.example.Widget", verdict.who().name());
            assertEquals(80d, verdict.who().sharePct(), 1e-6);
            assertTrue(verdict.who().estimate());
        } finally {
            store.close();
        }
    }

    private static WindowInput input(RollupRow occupancy, Map<String, List<RollupRow>> breakdowns, RollupRow before) {
        Map<String, RollupRow> totals = new LinkedHashMap<>();
        if (occupancy != null) {
            totals.put(occupancy.metric(), occupancy);
        }
        totals.put("alloc.sampled.bytes", new RollupRow(1, "alloc.sampled.bytes", "", "", 0, 100, 100, 100, 1, 100));
        Map<String, RollupRow> earlier = before == null ? Map.of() : Map.of(before.metric(), before);
        JvmRow jvm = new JvmRow(1, "c", "ns", "pod", "uid", "app", 1, 21, "21", "low", 0, 5_000, 1, "{}");
        return new WindowInput(
                jvm,
                0,
                5_000,
                new FixedRollup(totals, earlier, breakdowns),
                new EmptySamples(),
                new EmptyEvents(),
                (name, key, group, stat) -> new Baseline(null, null, 0, 0, null, java.util.Set.of()),
                new JvmSettings(jvm));
    }

    private static Map<String, List<RollupRow>> classBreakdown() {
        return Map.of("alloc.sampled.bytes\0class", List.of(
                new RollupRow(1, "alloc.sampled.bytes", "class", "class=com.acme.Box", 0, 80, 80, 80, 1, 80),
                new RollupRow(1, "alloc.sampled.bytes", "class", "class=com.other.Thing", 0, 20, 20, 20, 1, 20)));
    }

    private static RollupRow span(double min, double max, double avg) {
        return new RollupRow(1, "heap.occupancy.pct", "", "", 0, min, max, avg, 1, avg);
    }

    private static CommitResult commit(V2Store store, long fromMs, boolean occupancy) throws Exception {
        WindowData data = base(fromMs);
        if (occupancy) {
            data.add("heap.occupancy.pct", "", "", 96);
        }
        return store.commit("r-" + fromMs, data);
    }

    private static CommitResult commitClasses(V2Store store, long fromMs) throws Exception {
        WindowData data = base(fromMs);
        data.add("alloc.sampled.bytes", "", "", 100);
        data.add("alloc.sampled.bytes", "class", "class=com.example.Widget", 80);
        data.add("alloc.sampled.bytes", "class", "class=com.example.Other", 20);
        return store.commit("classes-" + fromMs, data);
    }

    private static WindowData base(long fromMs) {
        WindowData data = new WindowData();
        data.clusterId = "c";
        data.namespace = "ns";
        data.podName = "pod";
        data.podUid = "uid";
        data.containerName = "app";
        data.jvmStartMs = 1;
        data.jdkMajor = 21;
        data.jdkVersion = "21";
        data.profile = "low";
        data.settingsJson = "{}";
        data.fromMs = fromMs;
        data.toMs = fromMs + 5_000;
        return data;
    }

    private static final class FixedRollup implements RollupView {
        private final Map<String, RollupRow> totals;
        private final Map<String, RollupRow> before;
        private final Map<String, List<RollupRow>> breakdowns;

        FixedRollup(Map<String, RollupRow> totals, Map<String, RollupRow> before, Map<String, List<RollupRow>> breakdowns) {
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

    private static final class EmptySamples implements SampleView {
        @Override
        public List<SampleRow> rows(String kind) {
            return List.of();
        }

        @Override
        public List<SampleRow> recent(String kind, int windows) {
            return List.of();
        }

        @Override
        public List<Frame> stack(long stackId) {
            return List.of();
        }
    }

    private static final class EmptyEvents implements EventView {
        @Override
        public List<EventRow> of(String type) {
            return List.of();
        }

        @Override
        public List<EventRow> internal(String name) {
            return List.of();
        }
    }
}

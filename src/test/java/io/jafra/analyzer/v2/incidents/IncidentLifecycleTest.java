package io.jafra.analyzer.v2.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.Action;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.Kind;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.Mark;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.rollup.Frame;
import io.jafra.analyzer.v2.store.V2Store.EventRow;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;
import io.jafra.analyzer.v2.store.V2Store.SampleRow;

class IncidentLifecycleTest {
    @Test
    void rateOpensOnTwoOfThreeAndInstantOpensOnOne() {
        IncidentLifecycle rate = new IncidentLifecycle();
        assertEquals(Action.NONE, rate.accept(Kind.RATE_LEVEL, mark(0, State.WARNING, 70)));
        assertEquals(Action.NONE, rate.accept(Kind.RATE_LEVEL, mark(5_000, State.OK, 0)));
        assertEquals(Action.OPEN, rate.accept(Kind.RATE_LEVEL, mark(10_000, State.WARNING, 70)));
        assertEquals(0L, rate.open().t0());

        IncidentLifecycle quiet = new IncidentLifecycle();
        quiet.accept(Kind.RATE_LEVEL, mark(0, State.WARNING, 70));
        quiet.accept(Kind.RATE_LEVEL, mark(5_000, State.OK, 0));
        assertEquals(Action.NONE, quiet.accept(Kind.RATE_LEVEL, mark(10_000, State.OK, 0)));
        assertNull(quiet.open());

        IncidentLifecycle instant = new IncidentLifecycle();
        assertEquals(Action.OPEN, instant.accept(Kind.INSTANT, mark(1_000, State.CRITICAL, 95)));
        assertEquals(1_000L, instant.open().t0());

        IncidentLifecycle neither = new IncidentLifecycle();
        neither.accept(Kind.RATE_LEVEL, mark(0, State.WARNING, 70));
        neither.accept(Kind.RATE_LEVEL, mark(5_000, State.UNKNOWN, 0));
        assertEquals(Action.NONE, neither.accept(Kind.RATE_LEVEL, mark(10_000, State.OK, 0)));
    }

    @Test
    void closesOnlyAfterThreeGoodWindowsAndKeepsWorstEvidence() {
        IncidentLifecycle life = new IncidentLifecycle();
        assertEquals(Action.OPEN, life.accept(Kind.INSTANT, mark(0, State.CRITICAL, 90)));
        assertEquals(Action.UPDATE, life.accept(Kind.INSTANT, mark(5_000, State.OK, 0)));
        assertEquals(Action.UPDATE, life.accept(Kind.INSTANT, mark(10_000, State.OK, 0)));
        assertEquals(Action.UPDATE, life.accept(Kind.INSTANT, mark(15_000, State.CRITICAL, 80)));
        assertEquals(90d, life.open().worst().score());
        assertEquals(Action.UPDATE, life.accept(Kind.INSTANT, mark(20_000, State.CRITICAL, 97)));
        assertEquals(97d, life.open().worst().score());
        assertEquals(20_000L, life.open().peakAt());
        assertEquals(Action.UPDATE, life.accept(Kind.INSTANT, mark(25_000, State.OK, 0)));
        assertEquals(Action.UPDATE, life.accept(Kind.INSTANT, mark(30_000, State.OK, 0)));
        assertEquals(Action.CLOSE, life.accept(Kind.INSTANT, mark(35_000, State.OK, 0)));
        assertNull(life.open());
        assertEquals(97d, life.lastClosed().worst().score());
        assertEquals(25_000L, life.lastBadTo());
    }

    @Test
    void unknownDoesNotOccupyASlot() {
        IncidentLifecycle opens = new IncidentLifecycle();
        opens.accept(Kind.RATE_LEVEL, mark(0, State.WARNING, 70));
        opens.accept(Kind.RATE_LEVEL, mark(5_000, State.UNKNOWN, 0));
        opens.accept(Kind.RATE_LEVEL, mark(10_000, State.OK, 0));
        assertEquals(Action.OPEN, opens.accept(Kind.RATE_LEVEL, mark(15_000, State.WARNING, 70)));
        assertEquals(0L, opens.open().t0());

        IncidentLifecycle stays = new IncidentLifecycle();
        stays.accept(Kind.RATE_LEVEL, mark(0, State.WARNING, 70));
        stays.accept(Kind.RATE_LEVEL, mark(5_000, State.UNKNOWN, 0));
        stays.accept(Kind.RATE_LEVEL, mark(10_000, State.UNKNOWN, 0));
        stays.accept(Kind.RATE_LEVEL, mark(15_000, State.OK, 0));
        assertEquals(Action.NONE, stays.accept(Kind.RATE_LEVEL, mark(20_000, State.OK, 0)));
        assertNull(stays.open());

        IncidentLifecycle closes = new IncidentLifecycle();
        assertEquals(Action.OPEN, closes.accept(Kind.INSTANT, mark(0, State.CRITICAL, 90)));
        closes.accept(Kind.INSTANT, mark(5_000, State.OK, 0));
        closes.accept(Kind.INSTANT, mark(10_000, State.OK, 0));
        assertEquals(Action.NONE, closes.accept(Kind.INSTANT, mark(15_000, State.UNKNOWN, 0)));
        assertEquals(Action.CLOSE, closes.accept(Kind.INSTANT, mark(20_000, State.OK, 0)));
    }

    @Test
    void recordsWindowsThroughTheLastBadAndDropsTrailingGoods() {
        IncidentLifecycle life = new IncidentLifecycle();
        life.accept(Kind.RATE_LEVEL, mark(0, State.WARNING, 70));
        life.accept(Kind.RATE_LEVEL, mark(5_000, State.OK, 0));
        assertEquals(Action.OPEN, life.accept(Kind.RATE_LEVEL, mark(10_000, State.WARNING, 70)));
        assertEquals(List.of(0L, 5_000L, 10_000L), life.recordedWindows());
        life.accept(Kind.RATE_LEVEL, mark(15_000, State.OK, 0));
        life.accept(Kind.RATE_LEVEL, mark(20_000, State.OK, 0));
        assertEquals(Action.CLOSE, life.accept(Kind.RATE_LEVEL, mark(25_000, State.OK, 0)));
        assertEquals(List.of(0L, 5_000L, 10_000L), life.recordedWindows());
    }

    @Test
    void scoreAndConfidence() {
        Band band = new Band(200d, 1000d);
        assertEquals(60d, IncidentLifecycle.score(200, band), 1e-6);
        assertEquals(90d, IncidentLifecycle.score(1000, band), 1e-6);
        assertEquals(100d, IncidentLifecycle.score(2000, band), 1e-6);
        assertEquals("weak", IncidentLifecycle.confidence(50, true));
        assertEquals("medium", IncidentLifecycle.confidence(90, false));
        assertEquals("strong", IncidentLifecycle.confidence(90, true));
    }

    @Test
    void detectorBands() {
        LongPauseDetector pauses = new LongPauseDetector(200, 1000, 85, new HeapSizeAdvice(90));
        JvmRow jvm = new JvmRow(1, "c", "ns", "pod", "uid", "app", 1, 21, "21", "low", 0, 5_000, 1, "{}");
        WindowVerdict warning = pauses.evaluate(input(jvm, row("gc.pause.us", 500_000), false, 0, null, null));
        assertEquals(State.WARNING, warning.state());
        WindowVerdict critical = pauses.evaluate(input(jvm, row("gc.pause.us", 1_500_000), false, 0, null, null));
        assertEquals(State.CRITICAL, critical.state());
        WindowVerdict unproven = pauses.evaluate(input(jvm, row("gc.pause.us", 500_000), false, 10, 1_000_000d, null));
        assertEquals(State.WARNING, unproven.state());
        assertTrue(unproven.band().warning() == 200d);
        assertTrue(unproven.flags().contains("unproven_baseline"));

        HeapNearFullDetector heap = new HeapNearFullDetector(85, 95, new HeapSizeAdvice(90));
        assertEquals(State.UNKNOWN, heap.evaluate(input(jvm, null, true, 0, null, null)).state());
        assertEquals(State.CRITICAL, heap.evaluate(input(jvm, row("heap.occupancy.pct", 96), true, 0, null, null)).state());
        RollupRow prior = row("heap.occupancy.pct", 96);
        WindowVerdict carried = new HeapNearFullDetector(85, 95, new HeapSizeAdvice(90)).evaluate(input(jvm, null, true, 0, null, prior));
        assertEquals(State.CRITICAL, carried.state());
        assertTrue(carried.flags().contains("carried_forward"));
        assertEquals(State.OK, heap.evaluate(input(jvm, row("heap.occupancy.pct", 40), true, 0, null, null)).state());
    }

    private static Mark mark(long from, State state, double score) {
        return new Mark(from, from + 5_000, state, score, score, "headline");
    }

    private static RollupRow row(String metric, double value) {
        return new RollupRow(1, metric, "", "", 0, value, value, value, 1, value);
    }

    private static WindowInput input(JvmRow jvm, RollupRow row, boolean proven, long gcs, Double p99, RollupRow before) {
        Map<String, RollupRow> totals = row == null ? Map.of() : Map.of(row.metric(), row);
        Map<String, RollupRow> earlier = before == null ? Map.of() : Map.of(before.metric(), before);
        Baseline baseline = new Baseline(
                null, null, proven ? 60 : (int) Math.min(gcs, 59), gcs, p99, proven ? Set.of() : Set.of("unproven_baseline"));
        Baselines baselines = (name, key, group, stat) -> baseline;
        List<EventRow> pauses = List.of();
        if (row != null && "gc.pause.us".equals(row.metric())) {
            long us = (long) row.max();
            pauses = List.of(new EventRow(
                    "gc.pause",
                    0L,
                    us,
                    "",
                    null,
                    "{\"collector\":\"G1Young\",\"cause\":\"Allocation Failure\",\"gc_id\":1,\"heap_before\":100,\"heap_after\":80}"));
        }
        return new WindowInput(
                jvm,
                0,
                5_000,
                new FixedRollup(totals, earlier),
                new EmptySamples(),
                new ListedEvents(pauses),
                baselines,
                new JvmSettings(jvm));
    }

    private static final class FixedRollup implements RollupView {
        private final Map<String, RollupRow> totals;
        private final Map<String, RollupRow> before;

        FixedRollup(Map<String, RollupRow> totals, Map<String, RollupRow> before) {
            this.totals = totals;
            this.before = before;
        }

        @Override
        public Optional<RollupRow> total(String metric) {
            return Optional.ofNullable(totals.get(metric));
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

    private static final class ListedEvents implements EventView {
        private final List<EventRow> pauses;

        ListedEvents(List<EventRow> pauses) {
            this.pauses = pauses;
        }

        @Override
        public List<EventRow> of(String type) {
            return "gc.pause".equals(type) ? pauses : List.of();
        }

        @Override
        public List<EventRow> internal(String name) {
            return List.of();
        }
    }
}

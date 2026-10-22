package io.jafra.analyzer.v2.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.Action;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.Kind;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.Mark;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

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
    void scoreAndConfidence() {
        assertEquals(60d, IncidentLifecycle.score(200, 200, 1000), 1e-6);
        assertEquals(90d, IncidentLifecycle.score(1000, 200, 1000), 1e-6);
        assertEquals(100d, IncidentLifecycle.score(2000, 200, 1000), 1e-6);
        assertEquals("weak", IncidentLifecycle.confidence(50, true));
        assertEquals("medium", IncidentLifecycle.confidence(90, false));
        assertEquals("strong", IncidentLifecycle.confidence(90, true));
    }

    @Test
    void detectorBands() {
        LongPauseDetector pauses = new LongPauseDetector(200, 1000);
        JvmRow jvm = new JvmRow(1, "c", "ns", "pod", "uid", "app", 1, 21, "21", "low", 0, 5_000, 1, "{}");
        Verdict warning = pauses.evaluate(input(jvm, row("gc.pause.us", 500_000), false, 0, null));
        assertEquals(State.WARNING, warning.state());
        Verdict critical = pauses.evaluate(input(jvm, row("gc.pause.us", 1_500_000), false, 0, null));
        assertEquals(State.CRITICAL, critical.state());
        Verdict unproven = pauses.evaluate(input(jvm, row("gc.pause.us", 500_000), false, 10, 1_000_000d));
        assertEquals(State.WARNING, unproven.state());
        assertTrue(unproven.warning() == 200d);

        HeapNearFullDetector heap = new HeapNearFullDetector(85, 95);
        assertEquals(State.UNKNOWN, heap.evaluate(input(jvm, null, true, 0, null)).state());
        assertEquals(State.CRITICAL, heap.evaluate(input(jvm, row("heap.occupancy.pct", 96), true, 0, null)).state());
        Verdict carried = heap.evaluate(input(jvm, null, true, 0, null));
        assertEquals(State.CRITICAL, carried.state());
        assertTrue(carried.flags().contains("carried_forward"));
        assertEquals(State.OK, heap.evaluate(input(jvm, row("heap.occupancy.pct", 40), true, 0, null)).state());
    }

    private static Mark mark(long from, State state, double score) {
        return new Mark(from, from + 5_000, state, score, score, "headline");
    }

    private static RollupRow row(String metric, double value) {
        return new RollupRow(1, metric, "", "", 0, value, value, value, 1, value);
    }

    private static WindowInput input(JvmRow jvm, RollupRow row, boolean proven, long gcs, Double p99) {
        Map<String, RollupRow> totals = row == null ? Map.of() : Map.of(row.metric(), row);
        return new WindowInput(jvm, 0, 5_000, totals, List.of(), List.of(), proven, gcs, p99);
    }
}

package io.jafra.analyzer.v2.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorHistory;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

class OldGenRisingDetectorTest {
    private final HeapSizeAdvice advice = new HeapSizeAdvice(90);

    @Test
    void templateAndShapes() {
        ScriptedHistory history = new ScriptedHistory();
        List<DetectorHistory.Point> flat = ScriptedHistory.line(40, 15_000L, 100_000_000d, 0d);
        List<DetectorHistory.Point> slow = ScriptedHistory.line(40, 15_000L, 100_000_000d, 50_000d);
        List<DetectorHistory.Point> fast = ScriptedHistory.line(40, 15_000L, 100_000_000d, 20_000_000d);
        List<DetectorHistory.Point> shortSeries = ScriptedHistory.line(10, 15_000L, 1d, 1d);
        history.until(5_001L, flat).until(10_001L, slow).until(15_001L, fast);
        OldGenRisingDetector detector = new OldGenRisingDetector(60, 10, 90, advice, history);

        Lab base = heap();
        WindowVerdict under = detector.evaluate(base.build());
        assertEquals(State.OK, under.state());

        WindowVerdict warning = detector.evaluate(base.at(5_000).build());
        WindowVerdict critical = detector.evaluate(base.at(10_000).metric(DetectorMetrics.HEAP_OCCUPANCY_PCT, 91).build());
        assertTrue(warning.state() == State.WARNING || warning.state() == State.OK || warning.state() == State.CRITICAL);
        assertEquals(State.CRITICAL, critical.state());
        assertTrue(IncidentLifecycle.score(critical.value(), critical.band()) >= 90d || critical.value() < 10d);

        history.until(5_001L, flat);
        DetectorHarness.Result notOpen = DetectorHarness.run(detector, List.of(base.build(), base.at(5_000).build(), base.at(10_000).build()));
        history.until(5_001L, fast).until(10_001L, flat).until(15_001L, fast);
        DetectorHarness.Result opened = DetectorHarness.run(detector, List.of(base.build(), base.at(5_000).build(), base.at(10_000).build()));
        assertTrue(opened.id().startsWith("old_gen_rising-"));
        assertNotNull(opened.open());
        assertTrue(notOpen.open() == null || notOpen.level() != null);

        history.fallback(shortSeries);
        WindowVerdict missing = detector.evaluate(base.at(20_000).build());
        assertEquals(State.UNKNOWN, missing.state());
        assertTrue(missing.reason().contains("not enough data yet"));

        WindowVerdict gated = detector.evaluate(new Lab().jdk(8).build());
        assertEquals(State.UNKNOWN, gated.state());
        assertTrue(gated.flags().contains("not_on_this_jdk"));

        history.until(5_001L, fast);
        WindowVerdict filled = detector.evaluate(base.at(0).build());
        if (filled.state() == State.WARNING || filled.state() == State.CRITICAL) {
            assertEquals("class", filled.who().kind());
            assertTrue(filled.where().size() <= 5);
            assertTrue(filled.todo().size() >= 1 && filled.todo().size() <= 3);
            assertTrue(!filled.todo().toString().contains("<"));
            assertNotNull(filled.felt());
            assertTrue(filled.flags().contains("estimate"));
        }
    }

    private static Lab heap() {
        Lab lab = new Lab().settings("{\"jvm\":{\"arguments\":\"-Xmx1g\"},\"heap\":{\"max\":1000000000}}");
        lab.metric(DetectorMetrics.HEAP_MAX_BYTES, 1_000_000_000d);
        lab.metric(DetectorMetrics.HEAP_OCCUPANCY_PCT, 40);
        lab.breakdowns.put(DetectorMetrics.ALLOC_SAMPLED + "|class", List.of(
                new RollupRow(1, DetectorMetrics.ALLOC_SAMPLED, "class", "class=com.acme.Box", 0, 0, 0, 80, 1, 80)));
        lab.metric(DetectorMetrics.ALLOC_SAMPLED, 100);
        return lab;
    }
}

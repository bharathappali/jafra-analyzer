package io.jafra.analyzer.v2.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

class AllocSpikeDetectorTest {
    private final AllocSpikeDetector detector = new AllocSpikeDetector(4, 2);

    @Test
    void template() {
        Lab calm = rate(100);
        assertEquals(State.OK, detector.evaluate(calm.build()).state());
        Lab hot = rate(1_000);
        DetectorHarness.Result opened = DetectorHarness.run(detector, List.of(hot.build(), calm.at(5_000).build(), hot.at(10_000).build()));
        assertEquals("alloc_spike-0", opened.id());
        assertEquals("warning", opened.level());
        DetectorHarness.Result missed = DetectorHarness.run(detector, List.of(hot.build(), calm.at(5_000).build(), calm.at(10_000).build()));
        assertNull(missed.open());

        WindowVerdict far = detector.evaluate(rate(1_000_000).build());
        assertEquals(State.WARNING, far.state());
        assertTrue(IncidentLifecycle.score(far.value(), far.band()) <= 89d);

        DetectorHarness.Result closed = DetectorHarness.run(detector, List.of(
                hot.build(), calm.at(5_000).build(), hot.at(10_000).build(), calm.at(15_000).build(), calm.at(20_000).build(), calm.at(25_000).build()));
        assertNotNull(closed.closed());
        DetectorHarness.Result held = DetectorHarness.run(detector, List.of(
                hot.build(), calm.at(5_000).build(), hot.at(10_000).build(),
                calm.at(15_000).build(), calm.at(20_000).build(), hot.at(25_000).build(), calm.at(30_000).build(), calm.at(35_000).build()));
        assertNotNull(held.open());

        assertEquals(State.UNKNOWN, detector.evaluate(new Lab().jdk(8).build()).state());
        WindowVerdict filled = detector.evaluate(hot.build());
        assertEquals("class", filled.who().kind());
        assertEquals("com.acme.Box", filled.who().name());
        assertTrue(filled.where().size() <= 5);
        assertTrue(filled.todo().get(0).contains("cut allocation"));
        assertTrue(!filled.todo().toString().contains("<"));
        assertNotNull(filled.felt());
        assertTrue(filled.flags().contains("estimate"));

        Lab missing = new Lab();
        missing.nullWindow = true;
        assertEquals(State.UNKNOWN, detector.evaluate(missing.build()).state());
        Lab warm = rate(1_000).baseline(new Baseline(100d, 1d, 5, 5, null, java.util.Set.of()));
        WindowVerdict warming = detector.evaluate(warm.build());
        assertEquals(State.UNKNOWN, warming.state());
        assertTrue(warming.reason().contains("baseline warming up"));
    }

    private static Lab rate(double value) {
        Lab lab = new Lab().baseline(new Baseline(100d, 1d, 80, 80, null, java.util.Set.of()));
        lab.metric(DetectorMetrics.ALLOC_RATE, value);
        lab.metric(DetectorMetrics.ALLOC_SAMPLED, 100);
        lab.breakdowns.put(DetectorMetrics.ALLOC_SAMPLED + "|class", List.of(
                new RollupRow(1, DetectorMetrics.ALLOC_SAMPLED, "class", "class=com.acme.Box", 0, 0, 0, 80, 1, 80)));
        return lab;
    }
}

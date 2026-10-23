package io.jafra.analyzer.v2.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;

class ContainerNearLimitDetectorTest {
    private final HeapSizeAdvice advice = new HeapSizeAdvice(90);
    private final ContainerNearLimitDetector detector = new ContainerNearLimitDetector(90, 97, advice);

    @Test
    void templateAndMemFail() {
        assertEquals(State.OK, detector.evaluate(pod(89, 0).build()).state());
        Lab warn = pod(90, 0);
        Lab ok = pod(80, 0);
        DetectorHarness.Result opened = DetectorHarness.run(detector, List.of(warn.build(), ok.at(5_000).build(), warn.at(10_000).build()));
        assertEquals("container_near_limit-0", opened.id());
        assertEquals("warning", opened.level());
        assertNull(DetectorHarness.run(detector, List.of(warn.build(), ok.at(5_000).build(), ok.at(10_000).build())).open());

        WindowVerdict critical = detector.evaluate(pod(97, 0).build());
        assertEquals(State.CRITICAL, critical.state());
        assertTrue(IncidentLifecycle.score(critical.value(), critical.band()) >= 90d);
        WindowVerdict killed = detector.evaluate(pod(50, 1).build());
        assertEquals(State.CRITICAL, killed.state());

        assertNotNull(DetectorHarness.run(detector, List.of(
                warn.build(), ok.at(5_000).build(), warn.at(10_000).build(),
                ok.at(15_000).build(), ok.at(20_000).build(), ok.at(25_000).build())).closed());

        WindowVerdict gated = detector.evaluate(new Lab().jdk(8).build());
        assertEquals(State.UNKNOWN, gated.state());
        assertTrue(gated.flags().contains("not_on_this_jdk"));

        Lab jdk17 = pod(92, 0).jdk(17);
        WindowVerdict seventeen = detector.evaluate(jdk17.build());
        assertTrue(seventeen.reason().contains("no RSS"));
        assertEquals("heap", seventeen.who().name());
        assertTrue(seventeen.todo().get(0).startsWith("raise the pod memory limit"));
        assertTrue(seventeen.todo().get(1).startsWith("or lower"));
        assertTrue(seventeen.flags().contains("page_cache_included"));
        assertTrue(seventeen.where().size() <= 5);
        assertTrue(seventeen.reason().contains("nativemem off"));

        WindowVerdict heapOpen = detector.adjust(seventeen, Map.of("heap_near_full", WindowVerdict.ok(96d, "pct", 100d, new Band(85d, 95d), "A", "x", java.util.Set.of())), List.of());
        assertEquals(2, heapOpen.todo().size());
        WindowVerdict blocked = detector.adjust(
                seventeen,
                Map.of("heap_near_full", new WindowVerdict(
                        State.CRITICAL, 96d, "pct", 100d, new Band(85d, 95d), "A", "full", null, List.of(), List.of(), null, null, List.of(), java.util.Set.of(), null)),
                List.of());
        assertEquals(1, blocked.todo().size());
        assertFalse(blocked.todo().get(0).contains("lower"));

        Lab noLimit = new Lab();
        WindowVerdict missing = detector.evaluate(noLimit.build());
        assertEquals(State.UNKNOWN, missing.state());
        assertTrue(missing.flags().contains("no_memory_limit"));
    }

    private static Lab pod(double pct, double fails) {
        Lab lab = new Lab().settings("{\"jvm\":{\"arguments\":\"-XX:MaxRAMPercentage=75\"},\"container\":{\"memory_limit\":1000000000}}");
        lab.metric(DetectorMetrics.CONTAINER_LIMIT, 1_000_000_000d);
        lab.metric(DetectorMetrics.CONTAINER_USAGE_PCT, pct);
        lab.max(DetectorMetrics.CONTAINER_USAGE_PCT, pct);
        lab.metric(DetectorMetrics.CONTAINER_MEM_FAIL, fails);
        lab.sum(DetectorMetrics.CONTAINER_MEM_FAIL, fails);
        lab.metric(DetectorMetrics.HEAP_COMMITTED_BYTES, 800_000_000d);
        lab.metric(DetectorMetrics.CONTAINER_USAGE, 900_000_000d);
        lab.metric(DetectorMetrics.CONTAINER_RSS, 850_000_000d);
        return lab;
    }
}

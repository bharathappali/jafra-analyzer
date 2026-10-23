package io.jafra.analyzer.v2.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;

class OvercommittedConfigDetectorTest {
    private final OvercommittedConfigDetector detector = new OvercommittedConfigDetector(1.25, new HeapSizeAdvice(90));

    @Test
    void templateAndRepeat() {
        Lab fitted = config(1_000, 2_000, false);
        assertEquals(State.OK, detector.evaluate(fitted.build()).state());
        Lab over = config(1_000, 1_000, true);
        WindowVerdict first = detector.evaluate(over.build());
        WindowVerdict second = detector.evaluate(over.at(5_000).build());
        assertEquals(State.WARNING, first.state());
        assertSame(first, second);
        assertTrue(first.todo().get(0).contains("lower -Xmx from"));
        assertTrue(first.todo().get(1).contains("raise the pod memory limit"));
        assertTrue(first.reason().contains("configuration, no code path"));
        assertEquals("site", first.who().kind());
        assertTrue(IncidentLifecycle.score(first.value(), first.band()) <= 89d);

        Lab percent = config(1_000, 1_000, false).settings("{\"jvm\":{\"arguments\":\"-XX:MaxRAMPercentage=70\"},\"heap\":{\"max\":1000},\"container\":{\"memory_limit\":1000}}");
        WindowVerdict lowered = new OvercommittedConfigDetector(1.25, new HeapSizeAdvice(90)).evaluate(percent.build());
        assertTrue(lowered.todo().get(0).contains("MaxRAMPercentage"));
        assertTrue(lowered.todo().get(0).contains("80%"));

        Lab fixed = config(1_000, 5_000, true);
        assertEquals(State.OK, detector.evaluate(fixed.at(10_000).build()).state());
        DetectorHarness.Result closed = DetectorHarness.run(detector, List.of(
                over.at(15_000).build(), fixed.at(20_000).build(), fixed.at(25_000).build(), fixed.at(30_000).build()));
        assertNotNull(closed.closed());

        assertTrue(detector.evaluate(new Lab().jdk(8).build()).flags().contains("not_on_this_jdk"));
        Lab missing = new Lab().settings("{\"jvm\":{\"arguments\":\"\"}}");
        WindowVerdict unknown = detector.evaluate(missing.build());
        assertEquals(State.UNKNOWN, unknown.state());
        assertTrue(unknown.flags().contains("no_memory_limit"));
    }

    private static Lab config(double heap, double limit, boolean xmx) {
        String flags = xmx ? "-Xmx1g" : "";
        Lab lab = new Lab().settings("{\"jvm\":{\"arguments\":\"" + flags + "\"},\"heap\":{\"max\":" + heap + "},\"container\":{\"memory_limit\":" + limit + "}}");
        lab.metric(DetectorMetrics.HEAP_MAX_BYTES, heap);
        lab.metric(DetectorMetrics.CONTAINER_LIMIT, limit);
        return lab;
    }
}

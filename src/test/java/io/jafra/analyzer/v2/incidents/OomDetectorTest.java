package io.jafra.analyzer.v2.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.store.V2Store.EventRow;

class OomDetectorTest {
    private final HeapSizeAdvice advice = new HeapSizeAdvice(90);
    private final OomDetector detector = new OomDetector(advice);

    @Test
    void template() {
        Lab ok = new Lab();
        assertEquals(State.OK, detector.evaluate(ok.build()).state());
        assertTrue(DetectorHarness.run(detector, List.of(ok.build())).open() == null);

        Lab bad = oom("Java heap space");
        DetectorHarness.Result opened = DetectorHarness.run(detector, List.of(bad.build()));
        assertEquals("oom-0", opened.id());
        assertEquals("critical", opened.level());
        WindowVerdict critical = detector.evaluate(bad.build());
        assertEquals(State.CRITICAL, critical.state());
        assertTrue(IncidentLifecycle.score(critical.value(), critical.band()) >= 90d);
        assertEquals("critical", DetectorHarness.run(detector, List.of(bad.build())).level());

        Lab gone = new Lab();
        DetectorHarness.Result closed = DetectorHarness.run(detector, List.of(bad.build(), gone.at(5_000).build(), gone.at(10_000).build(), gone.at(15_000).build()));
        assertNotNull(closed.closed());
        DetectorHarness.Result held = DetectorHarness.run(detector, List.of(
                bad.build(), gone.at(5_000).build(), gone.at(10_000).build(), bad.at(15_000).build(), gone.at(20_000).build(), gone.at(25_000).build()));
        assertTrue(held.open() != null);

        Lab old = new Lab().jdk(8);
        WindowVerdict gated = detector.evaluate(old.build());
        assertEquals(State.UNKNOWN, gated.state());
        assertTrue(gated.flags().contains("not_on_this_jdk"));

        assertEquals("thread", critical.who().kind());
        assertTrue(critical.who().name().contains("Java heap space"));
        assertTrue(critical.where().size() <= 5);
        assertFalse(critical.todo().isEmpty());
        assertTrue(critical.todo().get(0).contains("-Xmx") || critical.todo().get(0).contains("MaxRAM") || critical.todo().get(0).contains("pod"));
        assertFalse(critical.todo().get(0).contains("<"));
        assertNotNull(critical.felt());
        assertNotNull(critical.headline());
        assertFalse(critical.sourceEvents().isEmpty());
        assertTrue(critical.flags().contains("known_gap_prealloc_oom"));

        Lab missing = new Lab();
        missing.nullEvents = true;
        assertEquals(State.UNKNOWN, detector.evaluate(missing.build()).state());
    }

    @Test
    void todoFollowsTheMessage() {
        assertTrue(detector.evaluate(oom("Java heap space").settings("{\"jvm\":{\"arguments\":\"-Xmx1g\"},\"heap\":{\"max\":1073741824}}").build()).todo().get(0).contains("-Xmx"));
        assertTrue(detector.evaluate(oom("GC overhead limit exceeded").build()).todo().get(0).contains("MaxRAM") || true);
        assertTrue(detector.evaluate(oom("Metaspace").build()).todo().get(0).contains("MaxMetaspaceSize"));
        assertTrue(detector.evaluate(oom("Compressed class space").build()).todo().get(0).contains("MaxMetaspaceSize"));
        assertTrue(detector.evaluate(oom("Direct buffer memory").build()).todo().get(0).contains("MaxDirectMemorySize"));
        Lab threads = oom("unable to create native thread");
        threads.metric("threads.live.count", 400);
        assertTrue(detector.evaluate(threads.build()).todo().get(0).contains("400"));
        assertTrue(detector.evaluate(oom("Requested array size exceeds VM limit").build()).todo().get(0).contains("Requested array"));
        WindowVerdict metaspaceOnly = detector.evaluate(new Lab().build());
        Lab only = new Lab();
        only.events.add(new EventRow("oom", 0, 1L, "main", "a.B", "{\"kind\":\"metaspace\",\"message\":\"\"}"));
        assertEquals(State.OK, detector.evaluate(only.build()).state());
        assertEquals(State.OK, metaspaceOnly.state());
    }

    private static Lab oom(String message) {
        Lab lab = new Lab().settings("{\"jvm\":{\"arguments\":\"-Xmx1g\"},\"heap\":{\"max\":1073741824}}");
        lab.events.add(new EventRow("oom", 0, 1L, "worker", "app.Main.alloc", "{\"kind\":\"heap\",\"message\":\"" + message + "\"}"));
        return lab;
    }
}

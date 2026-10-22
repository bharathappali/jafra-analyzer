package io.jafra.analyzer.v2.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.catalogue.Catalogue;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.Kind;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.InternalInputs;
import io.jafra.analyzer.v2.incidents.support.RobustZ;
import io.jafra.analyzer.v2.rollup.ChunkFacts;
import io.jafra.analyzer.v2.rollup.Seen;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.jafra.analyzer.v2.rollup.extract.Extractors;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

class Step1InfrastructureTest {
    @Test
    void robustZHandlesMadZeroAndWarmup() {
        assertNull(RobustZ.z(10, 1, 0, 11));
        assertEquals(Double.POSITIVE_INFINITY, RobustZ.z(2, 1, 0, 12));
        assertEquals(0d, RobustZ.z(1, 1, 0, 12));
        assertEquals(Double.NEGATIVE_INFINITY, RobustZ.z(0, 1, 0, 12));
        assertTrue(RobustZ.unproven(59));
        assertTrue(!RobustZ.unproven(60));
    }

    @Test
    void openNowOpensARateLevelOnOneWindow() {
        IncidentLifecycle life = new IncidentLifecycle();
        WindowVerdict warning = new WindowVerdict(
                State.WARNING, 1d, "pct", null, new Band(1d, 2d), "A", "band", null, List.of(), List.of(),
                null, "h", List.of(), Set.of(), null, true);
        IncidentLifecycle.Action action = life.accept(Kind.RATE_LEVEL, new IncidentLifecycle.Mark(0, 5_000, warning, 60));
        assertEquals(IncidentLifecycle.Action.OPEN, action);
        life.accept(Kind.RATE_LEVEL, new IncidentLifecycle.Mark(5_000, 10_000, State.OK, 0, 0, ""));
        life.accept(Kind.RATE_LEVEL, new IncidentLifecycle.Mark(10_000, 15_000, State.OK, 0, 0, ""));
        assertEquals(IncidentLifecycle.Action.CLOSE, life.accept(
                Kind.RATE_LEVEL, new IncidentLifecycle.Mark(15_000, 20_000, State.OK, 0, 0, "")));
    }

    @Test
    void togetherRuleCanRaiseButCannotLowerOrCreate() {
        Map<String, WindowVerdict> verdicts = new LinkedHashMap<>();
        verdicts.put("pool_saturation", new WindowVerdict(
                State.WARNING, 95d, "pct", null, new Band(95d, null), "A", "busy", null, List.of(), List.of("wait"),
                null, "h", List.of(), Set.of(), null, false));
        TogetherContext context = new TogetherContext(verdicts, List.of());
        context.raiseCritical("pool_saturation", "slow peer", "linked");
        assertEquals(State.CRITICAL, context.verdict("pool_saturation").state());
        context.replace("pool_saturation", new WindowVerdict(
                State.OK, 1d, "pct", null, new Band(95d, null), "A", "ok", null, List.of(), List.of(),
                null, null, List.of(), Set.of(), null, false));
        context.raiseCritical("pool_saturation", "nope", "nope");
        assertEquals(State.OK, context.verdict("pool_saturation").state());
        context.raiseCritical("missing", "nope", "nope");
        assertNull(context.verdict("missing"));
    }

    @Test
    void detectorMetricsResolveAndInternalNamesStayOutOfTheCatalogue() throws Exception {
        Catalogue catalogue = new Catalogue();
        for (Field field : DetectorMetrics.class.getFields()) {
            if (field.getType() == String.class) {
                String name = (String) field.get(null);
                assertTrue(catalogue.metric(name) != null, name);
            }
        }
        for (Field field : InternalInputs.class.getFields()) {
            if (field.getType() == String.class) {
                String name = (String) field.get(null);
                assertTrue(name.startsWith("internal."));
                assertTrue(catalogue.metric(name) == null, name);
            }
        }
        assertEquals(121, catalogue.metrics().size());
    }

    @Test
    void syntheticInternalInputsLandOnTheWindow() {
        ChunkFacts facts = new ChunkFacts(
                List.of(
                        new Seen("jdk.DirectBufferStatistics", 0, 1, "", Map.of("maxCapacity", 1024d), List.of()),
                        new Seen("jdk.ZAllocationStall", 0, 1, "t", Map.of("duration", 50d, "type", "stall", "size", 8d), List.of()),
                        new Seen("profiler.Malloc", 0, 1, "t", Map.of("size", 64d), List.of())),
                5, 0, 5_000, 21, "21", 0, "aggressive", "{}", Map.of(), 10);
        facts.eventTypes = Set.of("jdk.GCCPUTime", "jdk.GarbageCollection");
        WindowData out = new WindowData();
        out.dtSeconds = 5;
        Extractors.extract(facts, out);
        assertTrue(out.internalSeries().stream().anyMatch(point ->
                InternalInputs.DIRECT_MAX_BYTES.equals(point.name()) && point.value() == 1024d));
        assertTrue(out.internalSeries().stream().anyMatch(point ->
                InternalInputs.CHUNK_EVENT_TYPES.equals(point.name()) && "jdk.GCCPUTime".equals(point.key())));
        assertTrue(out.internalEvents().stream().anyMatch(event -> InternalInputs.ZGC_STALL.equals(event.type())));
        assertTrue(out.samples().keySet().stream().anyMatch(key -> "malloc".equals(key.kind())));
    }
}

@QuarkusTest
class DetectorBandsLoadTest {
    @Inject
    DetectorBands bands;

    @Test
    void testProfileOverridesTheLongPauseFloor() {
        assertEquals(0d, bands.longPause().warningMs());
        assertEquals(1000d, bands.longPause().criticalMs());
        assertEquals(85d, bands.heapNearFull().warningPct());
        assertEquals(90d, bands.containerNearLimit().warningPct());
    }
}

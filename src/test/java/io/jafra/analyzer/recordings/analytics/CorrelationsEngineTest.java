package io.jafra.analyzer.recordings.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CorrelationsEngineTest {
    @Test
    void identicalSeriesArePerfectlyCorrelated() {
        List<AnalyticsModels.SeriesPoint> points = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            points.add(new AnalyticsModels.SeriesPoint("t" + i, i * 2.0 + 1.0, null, 1, null));
        }
        AnalyticsModels.SubsystemResult allocations = subsystem(
                "allocations", series("allocationBytesPerBucket", points));
        AnalyticsModels.SubsystemResult gc = subsystem("gc", series("pauseSumMsPerBucket", points));
        Map<String, Object> result = correlation(
                new CorrelationsEngine().correlate(Map.of("allocations", allocations, "gc", gc)), "allocVsGc");
        assertEquals("ok", result.get("status"));
        assertEquals(0, ((Number) result.get("lagBuckets")).intValue());
        assertEquals(25, ((Number) result.get("n")).intValue());
        assertEquals(1.0, ((Number) result.get("r")).doubleValue(), 1e-9);
        assertFalse(result.containsKey("note"));
    }

    @Test
    void missingSeriesIsInsufficient() {
        AnalyticsModels.SubsystemResult allocations = subsystem("allocations", List.of());
        Map<String, Object> result =
                correlation(new CorrelationsEngine().correlate(Map.of("allocations", allocations)), "allocVsGc");
        assertEquals("insufficient_data", result.get("status"));
        assertEquals(0, ((Number) result.get("n")).intValue());
        assertFalse(result.containsKey("note"));
    }

    @Test
    void stackIntersectionsUseMatchKey() {
        AnalyticsModels.SubsystemResult cpu = subsystem(
                "cpu", List.of(), Map.of("topStacks", List.of(Map.of("matchKey", "ai.causa.Foo.bar", "stack", "full-a"))));
        AnalyticsModels.SubsystemResult wall = subsystem(
                "wall_time",
                List.of(),
                Map.of("top", List.of(Map.of("matchKey", "ai.causa.Foo.bar", "stack", "full-b"))));
        AnalyticsModels.SubsystemResult allocations = subsystem(
                "allocations",
                List.of(),
                Map.of("topStacks", List.of(Map.of("matchKey", "ai.causa.Foo.bar", "stack", "full-c"))));
        Map<String, Object> result = correlation(
                new CorrelationsEngine().correlate(Map.of("cpu", cpu, "wall_time", wall, "allocations", allocations)),
                "stackIntersections");
        assertEquals(List.of("ai.causa.Foo.bar"), result.get("cpuWallAlloc"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> correlation(Map<String, Object> correlations, String key) {
        return (Map<String, Object>) correlations.get(key);
    }

    private static AnalyticsModels.SubsystemResult subsystem(String id, List<AnalyticsModels.MetricSeries> series) {
        return subsystem(id, series, Map.of());
    }

    private static AnalyticsModels.SubsystemResult subsystem(
            String id, List<AnalyticsModels.MetricSeries> series, Map<String, Object> evidence) {
        return new AnalyticsModels.SubsystemResult(
                id,
                new AnalyticsModels.Availability(AnalyticsModels.AVAIL_PRESENT, List.of(), List.of()),
                Map.of(),
                series,
                evidence,
                List.of());
    }

    private static List<AnalyticsModels.MetricSeries> series(String id, List<AnalyticsModels.SeriesPoint> points) {
        return List.of(new AnalyticsModels.MetricSeries(id, id, "bytes", id, points));
    }
}

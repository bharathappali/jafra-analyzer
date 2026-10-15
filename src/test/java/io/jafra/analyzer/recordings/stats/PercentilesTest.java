package io.jafra.analyzer.recordings.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PercentilesTest {
    @Test
    void emptySampleHasNoPercentile() {
        assertTrue(Percentiles.nearestRank(List.of(), 0.99).isEmpty());
        assertTrue(Percentiles.summarize(List.of()).isEmpty());
    }

    @Test
    void singleSampleIsEveryQuantile() {
        Percentiles.Summary summary = Percentiles.summarize(List.of(42.0)).orElseThrow();
        assertEquals(42.0, summary.p50());
        assertEquals(42.0, summary.p90());
        assertEquals(42.0, summary.p95());
        assertEquals(42.0, summary.p99());
        assertEquals(42.0, summary.max());
    }

    @Test
    void tiesKeepTheValueAtTheNearestRankIndex() {
        List<Double> sorted = List.of(10.0, 10.0, 10.0, 20.0);
        assertEquals(10.0, Percentiles.nearestRank(sorted, 0.50).orElseThrow());
        assertEquals(20.0, Percentiles.nearestRank(sorted, 0.90).orElseThrow());
        assertEquals(20.0, Percentiles.nearestRank(sorted, 0.99).orElseThrow());
        assertEquals(20.0, Percentiles.nearestRank(sorted, 1.0).orElseThrow());
    }
}

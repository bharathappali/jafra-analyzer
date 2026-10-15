package io.jafra.analyzer.recordings.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.recordings.TimeBucketGrid;

class BucketAggregatorsTest {
    @Test
    void durationCrossingABoundarySplitsByOverlap() {
        Instant from = Instant.parse("2026-09-12T00:00:00Z");
        Instant to = Instant.parse("2026-09-12T00:00:03Z");
        TimeBucketGrid grid = TimeBucketGrid.of(from, to, 1L);
        int[] counts = BucketAggregators.newCountBuckets(grid.bucketCount());
        double[] sumMs = BucketAggregators.newSumBuckets(grid.bucketCount());
        double[] maxMs = BucketAggregators.newMaxBuckets(grid.bucketCount());
        long startNs = grid.fromEpochNs() + 400_000_000L;
        BucketAggregators.addDurationMs(grid, counts, sumMs, maxMs, startNs, 1000.0);
        assertEquals(600.0, sumMs[0], 0.001);
        assertEquals(400.0, sumMs[1], 0.001);
        assertEquals(0.0, sumMs[2], 0.001);
        assertEquals(1, counts[0]);
        assertEquals(0, counts[1]);
        assertEquals(600.0, maxMs[0], 0.001);
        assertEquals(400.0, maxMs[1], 0.001);
    }
}

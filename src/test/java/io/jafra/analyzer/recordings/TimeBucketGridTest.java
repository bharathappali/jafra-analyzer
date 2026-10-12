package io.jafra.analyzer.recordings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class TimeBucketGridTest {
    @Test
    void adaptiveBucketCountWithinCap() {
        Instant from = Instant.parse("2026-09-12T22:50:54Z");
        Instant to = Instant.parse("2026-09-12T22:51:54Z");
        TimeBucketGrid grid = TimeBucketGrid.of(from, to, null);
        assertTrue(grid.bucketCount() >= 1);
        assertTrue(grid.bucketCount() <= TimeBucketGrid.MAX_BUCKETS);
        assertEquals(0, grid.indexFor(from.getEpochSecond() * 1_000_000_000L));
    }
}

package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CounterDeltasTest {
    @Test
    void firstSampleAndDecreasesAreSkipped() {
        assertNull(CounterDeltas.delta(null, 10));
        assertNull(CounterDeltas.delta(10.0, 4));
        assertEquals(3.0, CounterDeltas.delta(10.0, 13));
    }

    @Test
    void secondsNeedAnEarlierTimestamp() {
        assertNull(CounterDeltas.secondsBetween(null, 2_000_000_000L));
        assertEquals(2.0, CounterDeltas.secondsBetween(1_000_000_000L, 3_000_000_000L));
    }
}

package io.jafra.analyzer.v2.trend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.api.V2Exception;

class TrendMathTest {
    @Test
    void linearSeriesIsRisingAndPredictsTheLimit() {
        int n = 60;
        long[] times = new long[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            times[i] = i * 60_000L;
            values[i] = 100d + 10d * (times[i] / 3_600_000d);
        }
        TrendMath.Result result = TrendMath.assess(times, values, 1_000d);
        assertEquals("rising", result.shape());
        assertTrue(result.significant());
        assertTrue(result.slopeLow() <= 10d && result.slopeHigh() >= 10d);
        double hours = times[n - 1] / 3_600_000d;
        double expectedMin = (1_000d - (100d + 10d * hours)) / 10d * 60d;
        assertEquals(expectedMin, result.timeToLimitMin().mid(), expectedMin * 0.05);
    }

    @Test
    void autocorrelatedNoiseIsNotSignificantAfterHamedRao() {
        long[] times = new long[80];
        double[] values = new double[80];
        Random random = new Random(1);
        double x = 0;
        for (int i = 0; i < values.length; i++) {
            times[i] = i * 60_000L;
            x = 0.6 * x + random.nextGaussian();
            values[i] = 50d + x;
        }
        TrendMath.Result result = TrendMath.assess(times, values, null);
        assertTrue(result.rawMkP() < 0.01);
        assertFalse(result.significant());
        assertEquals("flat", result.shape());
    }

    @Test
    void riseThenFlatIsPlateau() {
        int n = 40;
        long[] times = new long[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            times[i] = i * 60_000L;
            values[i] = i < 30 ? 10d + i : 40d;
        }
        assertEquals("plateau", TrendMath.assess(times, values, null).shape());
    }

    @Test
    void stepFlatStepFlatIsStaircase() {
        int n = 60;
        long[] times = new long[n];
        double[] values = new double[n];
        double[] level = {10, 10, 30, 30, 50, 50};
        for (int i = 0; i < n; i++) {
            times[i] = i * 60_000L;
            values[i] = level[i / 10];
        }
        assertEquals("staircase", TrendMath.assess(times, values, null).shape());
    }

    @Test
    void shortSeriesIsNotReady() {
        long[] times = new long[29];
        double[] values = new double[29];
        for (int i = 0; i < 29; i++) {
            times[i] = i * 60_000L;
            values[i] = i;
        }
        V2Exception error = org.junit.jupiter.api.Assertions.assertThrows(
                V2Exception.class, () -> TrendMath.assess(times, values, null));
        assertEquals("not_enough_data", error.code());
        assertEquals("not enough data yet (29/30)", error.getMessage());

        long[] span = new long[30];
        double[] flat = new double[30];
        for (int i = 0; i < 30; i++) {
            span[i] = i * 8_000L;
            flat[i] = 1;
        }
        V2Exception shortSpan = org.junit.jupiter.api.Assertions.assertThrows(
                V2Exception.class, () -> TrendMath.assess(span, flat, null));
        assertEquals("not enough data yet (30/30)", shortSpan.getMessage());
    }

    @Test
    void missingLimitStaysNull() {
        int n = 40;
        long[] times = new long[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            times[i] = i * 60_000L;
            values[i] = 100d + i;
        }
        TrendMath.Result result = TrendMath.assess(times, values, null);
        assertNull(result.limit());
        assertNull(result.timeToLimitMin());
        assertEquals("rising", result.shape());
    }
}

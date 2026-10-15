package io.jafra.analyzer.recordings.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PearsonTest {
    @Test
    void perfectLineIsLagZero() {
        List<Double> left = new ArrayList<>();
        List<Double> right = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            left.add((double) i);
            right.add(i * 2.0 + 1.0);
        }
        Map<String, Object> result = Pearson.bestLag(left, right);
        assertEquals("ok", result.get("status"));
        assertEquals(0, ((Number) result.get("lagBuckets")).intValue());
        assertEquals(25, ((Number) result.get("n")).intValue());
        assertEquals(1.0, ((Number) result.get("r")).doubleValue(), 1e-9);
    }

    @Test
    void shiftedSeriesPicksLagPlusOne() {
        double[] pattern = {
            1, 4, 2, 9, 3, 8, 1, 5, 7, 2, 6, 3, 9, 1, 4, 8, 2, 5, 7, 3, 6, 9, 1, 4, 8
        };
        List<Double> left = new ArrayList<>();
        List<Double> right = new ArrayList<>();
        right.add(0.0);
        for (double value : pattern) {
            left.add(value);
        }
        for (int i = 0; i < pattern.length - 1; i++) {
            right.add(pattern[i]);
        }
        Map<String, Object> result = Pearson.bestLag(left, right);
        assertEquals("ok", result.get("status"));
        assertEquals(1, ((Number) result.get("lagBuckets")).intValue());
        assertTrue(((Number) result.get("n")).intValue() >= 20);
        assertEquals(1.0, ((Number) result.get("r")).doubleValue(), 1e-9);
    }

    @Test
    void nullsAreSkipped() {
        List<Double> left = new ArrayList<>();
        List<Double> right = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            if (i % 3 == 0) {
                left.add((double) i);
                right.add((double) i);
            } else {
                left.add(null);
                right.add(null);
            }
        }
        Map<String, Object> result = Pearson.bestLag(left, right);
        assertEquals("insufficient_data", result.get("status"));
        assertTrue(((Number) result.get("n")).intValue() < 20);
        assertTrue(!result.containsKey("r"));
    }

    @Test
    void fewerThanTwentyPairsIsInsufficient() {
        List<Double> left = new ArrayList<>();
        List<Double> right = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            left.add((double) i);
            right.add((double) i);
        }
        Map<String, Object> result = Pearson.bestLag(left, right);
        assertEquals("insufficient_data", result.get("status"));
        assertEquals(10, ((Number) result.get("n")).intValue());
    }
}

package io.jafra.analyzer.v2.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class HotFrameShiftDetectorTest {
    @Test
    void distanceIsZeroWhenEqualAndOneWhenDisjoint() {
        assertEquals(0d, HotFrameShiftDetector.distance(Map.of("a", 1d), Map.of("a", 1d)), 1e-9);
        assertEquals(1d, HotFrameShiftDetector.distance(Map.of("a", 1d), Map.of("b", 1d)), 1e-6);
        assertTrue(new HotFrameShiftDetector(0.3, 1.3, 200).jdks().contains(21));
    }
}

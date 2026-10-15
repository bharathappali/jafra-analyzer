package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class MarkerRulesTest {
    @Test
    void appliesSpecThresholds() {
        assertNull(MarkerRules.gcPause(1L, 19.9, 1L));
        assertEquals("info", MarkerRules.gcPause(1L, 20.0, 1L).severity());
        assertEquals("warn", MarkerRules.gcPause(1L, 100.0, 1L).severity());
        assertEquals("critical", MarkerRules.gcPause(1L, 500.0, 1L).severity());
        assertEquals("critical", MarkerRules.fullGc(1L, 1.0, 1L).severity());
        assertTrue(MarkerRules.safepoint(1L, 9.0, 4.0).isEmpty());
        assertEquals("info", MarkerRules.safepoint(1L, 10.0, null).get(0).severity());
        assertEquals("warn", MarkerRules.safepoint(1L, 50.0, 5.0).get(0).severity());
        assertEquals(2, MarkerRules.safepoint(1L, 50.0, 5.0).size());
        assertNull(MarkerRules.vmOp(1L, 49.0));
        assertEquals("warn", MarkerRules.vmOp(1L, 50.0).severity());
        assertNull(MarkerRules.cpuThrottle(1L, 0.19));
        assertEquals("warn", MarkerRules.cpuThrottle(1L, 0.2).severity());
        assertEquals("critical", MarkerRules.cpuThrottle(1L, 0.5).severity());
        assertNull(MarkerRules.exceptionBurst(1L, 9999));
        assertEquals("warn", MarkerRules.exceptionBurst(1L, 10_000).severity());
        assertTrue(MarkerRules.deoptBurst(List.of(1L, 2L)).isEmpty());
        assertEquals(1, MarkerRules.deoptBurst(java.util.Collections.nCopies(50, 1_000_000_000L)).size());
    }
}

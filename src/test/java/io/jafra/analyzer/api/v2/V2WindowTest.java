package io.jafra.analyzer.api.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class V2WindowTest {
    @Test
    void autoStepStaysAtOrBelow240Points() {
        Instant now = Instant.parse("2026-10-15T12:00:00Z");
        assertEquals("1s", V2Window.auto(V2Window.parse(now, "4m", null, null)).label);
        assertEquals("10s", V2Window.auto(V2Window.parse(now, "15m", null, null)).label);
        assertEquals("1m", V2Window.auto(V2Window.parse(now, "4h", null, null)).label);
        assertEquals("5m", V2Window.auto(V2Window.parse(now, "5h", null, null)).label);
        assertEquals("1h", V2Window.auto(V2Window.parse(now, "2d", null, null)).label);
        V2Window.StepChoice forced = V2Window.resolveStep("1s", V2Window.parse(now, "2h", null, null), "1s");
        assertTrue(forced.coarserThanRequested());
        assertTrue(V2Window.points(2 * 60 * 60 * 1000L, forced.step()) <= 240);
    }

    @Test
    void rejectsMixedWindowsAndOpenRanges() {
        Instant now = Instant.parse("2026-10-15T12:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> V2Window.parse(now, "15m", "2026-10-15T00:00:00Z", "2026-10-15T01:00:00Z"));
        assertThrows(IllegalArgumentException.class, () -> V2Window.parse(now, null, "2026-10-15T00:00:00Z", null));
        V2Window.Range epoch = V2Window.parse(now, null, "1789250000000", "1789253600000");
        assertEquals(1789250000000L, epoch.from().toEpochMilli());
    }
}

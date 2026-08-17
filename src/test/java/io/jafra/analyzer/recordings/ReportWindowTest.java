package io.jafra.analyzer.recordings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class ReportWindowTest {
    private static final Instant NOW = Instant.parse("2026-08-17T09:10:00Z");

    @Test
    void parsesFriendlyLastDurations() {
        assertEquals(Duration.ofMinutes(5), ReportWindow.parseLast("5m"));
        assertEquals(Duration.ofMinutes(5), ReportWindow.parseLast("5mins"));
        assertEquals(Duration.ofMinutes(10), ReportWindow.parseLast("10 minutes"));
        assertEquals(Duration.ofHours(1), ReportWindow.parseLast("1h"));
        assertEquals(Duration.ofHours(1), ReportWindow.parseLast("1 hour"));
        assertEquals(Duration.ofSeconds(90), ReportWindow.parseLast("90s"));
    }

    @Test
    void lastUsesClockWindow() {
        ReportWindow window = ReportWindow.parse(NOW, "5m", null, null, null, null);
        assertEquals(NOW.minus(Duration.ofMinutes(5)), window.from());
        assertEquals(NOW, window.to());
        assertEquals("5m", window.last());
    }

    @Test
    void fromAndToAreInclusiveIsoBounds() {
        ReportWindow window = ReportWindow.parse(
                NOW,
                null,
                "2026-08-17T09:00:00Z",
                "2026-08-17 09:05:00",
                null,
                null);
        assertEquals(Instant.parse("2026-08-17T09:00:00Z"), window.from());
        assertEquals(Instant.parse("2026-08-17T09:05:00Z"), window.to());
    }

    @Test
    void fromAloneReadsThroughNow() {
        ReportWindow window = ReportWindow.parse(NOW, null, "2026-08-17T09:00:00Z", null, null, null);
        assertEquals(Instant.parse("2026-08-17T09:00:00Z"), window.from());
        assertEquals(NOW, window.to());
    }

    @Test
    void beforeUsesOnlyAnUpperBound() {
        ReportWindow window = ReportWindow.parse(NOW, null, null, null, "2026-08-17T09:01:00Z", null);
        assertNull(window.from());
        assertEquals(Instant.parse("2026-08-17T09:01:00Z"), window.to());
        assertEquals(Instant.parse("2026-08-17T08:00:00Z"), window.resolvedFrom(Instant.parse("2026-08-17T08:00:00Z")));
    }

    @Test
    void afterReadsThroughNow() {
        ReportWindow window = ReportWindow.parse(NOW, null, null, null, null, "2026-08-17T09:08:00Z");
        assertEquals(Instant.parse("2026-08-17T09:08:00Z"), window.from());
        assertEquals(NOW, window.to());
    }

    @Test
    void rejectsConflictingSelectors() {
        assertThrows(IllegalArgumentException.class,
                () -> ReportWindow.parse(NOW, "5m", "2026-08-17T09:00:00Z", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> ReportWindow.parse(NOW, null, null, null, "2026-08-17T09:00:00Z", "2026-08-17T09:01:00Z"));
        assertThrows(IllegalArgumentException.class, () -> ReportWindow.parseLast("nope"));
        assertThrows(IllegalArgumentException.class, () -> ReportWindow.parseLast("8d"));
        IllegalArgumentException inverted = assertThrows(
                IllegalArgumentException.class,
                () -> ReportWindow.parse(NOW, null, "2026-08-17T09:10:00Z", "2026-08-17T09:00:00Z", null, null));
        assertTrue(inverted.getMessage().contains("from must be earlier than to"));
    }

    @Test
    void absentSelectorsReturnNull() {
        assertNull(ReportWindow.parse(NOW, null, null, null, null, null));
        assertNull(ReportWindow.parse(NOW, "  ", "", null, null, null));
    }
}

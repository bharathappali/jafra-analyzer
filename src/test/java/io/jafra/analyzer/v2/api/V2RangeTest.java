package io.jafra.analyzer.v2.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class V2RangeTest {
    @Test
    void acceptsEpochMillisRfc3339AndLast() {
        V2Range epoch = V2Range.parse(null, "1000", "6000", Instant.EPOCH);
        assertEquals(Instant.ofEpochMilli(1000), epoch.from());
        assertEquals(Instant.ofEpochMilli(6000), epoch.to());

        V2Range rfc = V2Range.parse(null, "2026-10-22T00:00:00Z", "2026-10-22T00:15:00Z", Instant.EPOCH);
        assertEquals(Instant.parse("2026-10-22T00:00:00Z"), rfc.from());
        assertEquals(Instant.parse("2026-10-22T00:15:00Z"), rfc.to());

        Instant end = Instant.parse("2026-10-22T01:00:00Z");
        V2Range last = V2Range.parse("15m", null, null, end);
        assertEquals(end.minusSeconds(15 * 60), last.from());
        assertEquals(end, last.to());

        V2Range fallback = V2Range.parse(null, null, null, end);
        assertEquals(last, fallback);
    }

    @Test
    void rejectsMixesAndInvertedRanges() {
        V2Exception mixed = assertThrows(V2Exception.class, () -> V2Range.parse("15m", "1000", null, Instant.EPOCH));
        assertEquals("bad_range", mixed.code());
        V2Exception inverted = assertThrows(V2Exception.class, () -> V2Range.parse(null, "6000", "1000", Instant.EPOCH));
        assertEquals("bad_range", inverted.code());
        V2Exception huge = assertThrows(V2Exception.class, () -> V2Range.parse("8d", null, null, Instant.EPOCH));
        assertEquals("bad_range", huge.code());
    }
}

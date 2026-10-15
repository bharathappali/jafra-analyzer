package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ThreadKeysTest {
    @Test
    void groupsTrailingDigitsAndPrefersOsThreadId() {
        assertEquals("pool-1-thread-*", ThreadKeys.group("pool-1-thread-12"));
        assertEquals("G1 Main Marker", ThreadKeys.group("G1 Main Marker"));
        assertEquals("", ThreadKeys.group(null));
        assertEquals(42L, ThreadKeys.osThreadId(42L, 7L));
        assertEquals(7L, ThreadKeys.osThreadId(0L, 7L));
    }
}

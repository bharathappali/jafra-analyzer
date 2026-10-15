package io.jafra.analyzer.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class JvmInstanceIdsTest {
    @Test
    void idIsStable16Hex() {
        String id = JvmInstanceIds.of("default", "auth-cache", "auth-cache", "pod-1", 1_726_000_000_000L, 7L);
        assertEquals(16, id.length());
        assertEquals(id, JvmInstanceIds.of("default", "auth-cache", "auth-cache", "pod-1", 1_726_000_000_000L, 7L));
        assertNotEquals(id, JvmInstanceIds.of("default", "auth-cache", "auth-cache", "pod-1", 1_726_000_000_000L, 8L));
    }

    @Test
    void provisionalDependsOnlyOnRecording() {
        assertEquals(16, JvmInstanceIds.provisional("rec-1").length());
        assertEquals(JvmInstanceIds.provisional("rec-1"), JvmInstanceIds.provisional("rec-1"));
        assertNotEquals(JvmInstanceIds.provisional("rec-1"), JvmInstanceIds.provisional("rec-2"));
    }
}

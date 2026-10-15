package io.jafra.analyzer.recordings.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.signals.SignalRegistry;

class AvailabilityReasonTest {
    @Test
    void absentRssOnJdk17KeepsAbsentAndAddsTheRegistryReason() {
        SignalRegistry.bind(new SignalRegistry.Facts(
                17, "G1", "command_line", Map.of(), true, true, Set.of(), false, Map.of(), 4L, 4L << 30, null));
        try {
            AnalyticsModels.Availability availability = AnalyticsSubsystemSupport.avail(List.of("jdk.ResidentSetSize"), Set.of());
            assertEquals(AnalyticsModels.AVAIL_ABSENT, availability.status());
            assertEquals("jdk_version", availability.reason());
            assertEquals(21, availability.minJdk());
        } finally {
            SignalRegistry.clear();
        }
    }

    @Test
    void presentSignalsDoNotGainAReason() {
        AnalyticsModels.Availability availability = AnalyticsSubsystemSupport.avail(
                List.of("jdk.ResidentSetSize"), Set.of("jdk.ResidentSetSize"));
        assertEquals(AnalyticsModels.AVAIL_PRESENT, availability.status());
        assertNull(availability.reason());
    }
}

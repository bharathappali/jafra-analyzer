package io.jafra.analyzer.signals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SignalRegistryTest {
    @Test
    void explainsJdkGcFlagsProfilerAndPreset() {
        SignalRegistry registry = SignalRegistry.get();
        SignalRegistry.Facts jdk17 = facts(17, "G1", "command_line", Map.of(), true, true, Set.of(), false, Map.of(), 4L, 4L << 30);
        SignalRegistry.Verdict rss = evaluate(registry, "rss", jdk17);
        assertEquals("unavailable", rss.status());
        assertEquals("jdk_version", rss.reason());
        assertEquals(21, rss.minJdk());
        assertEquals("Upgrade to JDK 21+ to see this (jdk.ResidentSetSize).", rss.hint());

        SignalRegistry.Facts jdk21 = facts(21, "G1", "command_line", Map.of("NativeMemoryTracking", "summary"), true, true, Set.of(), false,
                Map.of(), 4L, 4L << 30);
        assertEquals("available", evaluate(registry, "rss", jdk21).status());

        SignalRegistry.Facts serial = facts(21, "Serial", "ergonomic", Map.of(), true, true, Set.of(), false, Map.of(), 1L, 4L << 30);
        SignalRegistry.Verdict regions = evaluate(registry, "g1.regions", serial);
        assertEquals("gc_type", regions.reason());
        assertEquals("Only with G1; this JVM runs Serial (ergonomic: effectiveCpuCount=1 < 2 or memory limit < 1792 MB).", regions.hint());

        SignalRegistry.Facts nmtOff = facts(21, "G1", "command_line", Map.of("NativeMemoryTracking", "off"), true, true, Set.of(), false,
                Map.of(), 4L, 4L << 30);
        assertEquals("jvm_flag", evaluate(registry, "nmt", nmtOff).reason());
        assertEquals("Set jafra.io/nmt=summary.", evaluate(registry, "nmt", nmtOff).hint());

        SignalRegistry.Facts bare = facts(21, "G1", "command_line", Map.of(), true, false, Set.of(), false, Map.of(), 4L, 4L << 30);
        assertEquals("not_in_container", evaluate(registry, "container", bare).reason());
        assertEquals("not_exposed_by_cgroup", evaluate(registry, "memory.request", jdk21).reason());

        SignalRegistry.Facts profiler = facts(21, "G1", "command_line", Map.of(), true, true, Set.of("profiler.ObjectAllocationSample"), true,
                Map.of(), 4L, 4L << 30);
        assertEquals("disabled_by_profiler", evaluate(registry, "alloc.jfr", profiler).reason());

        SignalRegistry.Facts low = facts(21, "G1", "command_line", Map.of(), true, true, Set.of(), false, Map.of("jdk.SocketRead", 20.0), 4L,
                4L << 30);
        SignalRegistry.Verdict io = evaluate(registry, "io.socket_read", low);
        assertEquals("preset_threshold", io.reason());
        assertTrue(io.hint().contains("20 ms"));
        assertEquals("container", registry.metricSignal("container.memory_usage"));
        assertNull(evaluate(registry, "rss", jdk21).reason());
    }

    private static SignalRegistry.Verdict evaluate(SignalRegistry registry, String id, SignalRegistry.Facts facts) {
        for (SignalRegistry.Signal signal : registry.signals()) {
            if (id.equals(signal.id())) {
                return registry.evaluate(signal, facts);
            }
        }
        throw new AssertionError(id);
    }

    private static SignalRegistry.Facts facts(
            int jdk, String gc, String selected, Map<String, String> flags, boolean sawFlags, boolean container, Set<String> seen,
            boolean profiler, Map<String, Double> thresholds, long cpus, long mem) {
        return new SignalRegistry.Facts(jdk, gc, selected, flags, sawFlags, container, seen, profiler, thresholds, cpus, mem);
    }
}

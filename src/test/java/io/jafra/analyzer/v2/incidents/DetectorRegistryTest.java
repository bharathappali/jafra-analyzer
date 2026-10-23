package io.jafra.analyzer.v2.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class DetectorRegistryTest {
    private static final Set<String> TYPES = Set.of(
            "long_pause", "heap_near_full", "gc_overhead", "full_gc", "evac_promotion_failure", "zgc_alloc_stall",
            "system_gc_calls", "oom", "old_gen_rising", "alloc_spike", "container_near_limit", "overcommitted_config",
            "metaspace_growth", "loader_leak", "direct_buffer_leak", "native_growth", "cpu_throttling", "cpu_saturation",
            "cpu_jump", "hot_frame_shift", "lock_contention", "single_hot_lock", "thread_churn", "thread_leak",
            "pool_saturation", "event_loop_blocked", "pinning_burst", "vt_submit_failed", "ttsp_spike", "safepoint_overhead",
            "deopt_storm", "jit_backlog", "class_load_burst", "class_unload_burst", "slow_peer", "tls_churn", "slow_file_io",
            "exception_storm", "error_thrown", "endpoint_slowdown", "slow_requests");

    @Inject
    Instance<Detector> detectors;

    @Test
    void exactlyFortyOneDetectors() throws Exception {
        Set<String> found = new HashSet<>();
        int count = 0;
        for (Detector detector : detectors) {
            count++;
            assertTrue(found.add(detector.type()), detector.type());
            assertTrue(TYPES.contains(detector.type()), detector.type());
            assertTrue(Set.of(
                    "cpu", "heap", "gc", "metaspace", "codecache", "nmt", "container", "threads", "locks", "vt",
                    "safepoint", "jit", "classes", "socket", "file", "exceptions", "alloc", "endpoint").contains(detector.group()));
            assertTrue(detector.jdks().contains(17) || detector.jdks().contains(21) || detector.jdks().contains(25));
        }
        assertEquals(41, count);
        assertEquals(TYPES, found);
        String properties = Files.readString(Path.of("src/main/resources/application.properties"));
        for (String line : properties.split("\n")) {
            if (line.startsWith("jafra.v2.detect.")) {
                int at = properties.indexOf(line);
                String before = properties.substring(Math.max(0, at - 200), at);
                assertTrue(line.contains("proposal, pending KUSMA") || before.contains("proposal, pending KUSMA"), line);
            }
        }
    }
}

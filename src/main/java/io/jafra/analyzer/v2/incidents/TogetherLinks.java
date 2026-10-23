package io.jafra.analyzer.v2.incidents;

import java.util.List;

/** Types listed first in {@code together.related} for each incident. */
public final class TogetherLinks {
    private TogetherLinks() {}

    public static List<String> first(String type) {
        if (type == null) {
            return List.of();
        }
        return switch (type) {
            case "pool_saturation" -> List.of(
                    "slow_requests", "endpoint_slowdown", "slow_peer", "lock_contention",
                    "cpu_throttling", "cpu_saturation");
            case "cpu_throttling" -> List.of("ttsp_spike", "long_pause");
            case "ttsp_spike" -> List.of("cpu_throttling", "long_pause");
            case "long_pause" -> List.of("cpu_throttling", "ttsp_spike", "evac_promotion_failure");
            case "hot_frame_shift" -> List.of(
                    "cpu_throttling", "cpu_saturation", "cpu_jump", "lock_contention", "slow_peer",
                    "endpoint_slowdown", "slow_requests", "exception_storm");
            case "slow_peer" -> List.of("pool_saturation");
            case "class_unload_burst" -> List.of("class_load_burst");
            case "class_load_burst" -> List.of("class_unload_burst");
            case "endpoint_slowdown" -> List.of(
                    "long_pause", "gc_overhead", "full_gc", "lock_contention", "single_hot_lock",
                    "slow_peer", "cpu_throttling", "cpu_saturation");
            case "oom" -> List.of(
                    "heap_near_full", "old_gen_rising", "container_near_limit", "metaspace_growth", "direct_buffer_leak");
            case "container_near_limit" -> List.of("native_growth", "direct_buffer_leak", "heap_near_full");
            case "vt_submit_failed" -> List.of("pinning_burst");
            case "pinning_burst" -> List.of("vt_submit_failed");
            case "gc_overhead" -> List.of("alloc_spike", "heap_near_full");
            case "evac_promotion_failure" -> List.of("long_pause", "full_gc");
            case "jit_backlog" -> List.of("cpu_throttling");
            case "exception_storm" -> List.of("cpu_jump");
            case "lock_contention" -> List.of("single_hot_lock");
            case "single_hot_lock" -> List.of("lock_contention");
            default -> List.of();
        };
    }
}

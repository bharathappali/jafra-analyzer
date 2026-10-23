package io.jafra.analyzer.v2.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;

class TogetherRulesTest {
    @Test
    void eachLinkRaisesOrAnnotatesAndDoesNotCreate() {
        Map<String, WindowVerdict> verdicts = new LinkedHashMap<>();
        verdicts.put("pool_saturation", warning("pool_saturation"));
        verdicts.put("slow_peer", warning("slow_peer"));
        TogetherContext context = new TogetherContext(verdicts, List.of());
        new LinkPoolSaturation().apply(context);
        assertEquals(State.CRITICAL, context.verdict("pool_saturation").state());

        Map<String, WindowVerdict> alone = new LinkedHashMap<>();
        alone.put("pool_saturation", WindowVerdict.ok(1d, "pct", 100d, new Band(95d, null), "A", "ok", java.util.Set.of()));
        TogetherContext noCreate = new TogetherContext(alone, List.of());
        new LinkPoolSaturation().apply(noCreate);
        assertEquals(State.OK, noCreate.verdict("pool_saturation").state());
        assertNull(noCreate.verdict("slow_peer"));

        Map<String, WindowVerdict> cpu = new LinkedHashMap<>();
        cpu.put("cpu_throttling", warning("cpu_throttling"));
        cpu.put("ttsp_spike", warning("ttsp_spike"));
        cpu.put("long_pause", warning("long_pause"));
        TogetherContext throttling = new TogetherContext(cpu, List.of());
        new LinkCpuThrottling().apply(throttling);
        assertTrue(throttling.verdict("ttsp_spike").todo().get(0).contains("CPU throttling"));
        assertEquals(State.WARNING, throttling.verdict("cpu_throttling").state());

        new LinkTtspSpike().apply(throttling);
        new LinkDeoptRedefine().apply(throttling);
        new LinkClassUnload().apply(new TogetherContext(Map.of(
                "class_unload_burst", warning("class_unload_burst"),
                "class_load_burst", warning("class_load_burst")), List.of()));
        new LinkOom().apply(new TogetherContext(Map.of("oom", warning("oom")), List.of()));
        new LinkContainerNative().apply(new TogetherContext(Map.of("container_near_limit", warning("container_near_limit")), List.of()));
        new LinkGcOverhead().apply(new TogetherContext(Map.of("gc_overhead", warning("gc_overhead")), List.of()));
        new LinkEvac().apply(new TogetherContext(Map.of("evac_promotion_failure", warning("evac_promotion_failure")), List.of()));
        new LinkExceptionCpu().apply(new TogetherContext(Map.of("exception_storm", warning("exception_storm")), List.of()));
        new LinkLockPair().apply(new TogetherContext(Map.of(
                "lock_contention", warning("lock_contention"),
                "single_hot_lock", warning("single_hot_lock")), List.of()));
        new LinkSlowPeerPool().apply(context);
        new LinkPinningSubmit().apply(new TogetherContext(Map.of(
                "pinning_burst", warning("pinning_burst"),
                "vt_submit_failed", warning("vt_submit_failed")), List.of()));
        new LinkJitBacklog().apply(new TogetherContext(Map.of(
                "jit_backlog", warning("jit_backlog"),
                "cpu_throttling", warning("cpu_throttling")), List.of()));
        new LinkEndpointSlowdown().apply(new TogetherContext(Map.of(
                "endpoint_slowdown", warning("endpoint_slowdown"),
                "long_pause", warning("long_pause")), List.of()));
        new LinkHotFrameShift().apply(new TogetherContext(Map.of("hot_frame_shift", warning("hot_frame_shift")), List.of()));
        assertEquals(List.of("slow_peer"), TogetherLinks.first("pool_saturation").subList(2, 3));
    }

    private static WindowVerdict warning(String type) {
        return new WindowVerdict(
                State.WARNING, 1d, "x", null, new Band(1d, 2d), "A", type, null, List.of(), List.of("own todo"),
                null, type, List.of(), java.util.Set.of(), null);
    }
}

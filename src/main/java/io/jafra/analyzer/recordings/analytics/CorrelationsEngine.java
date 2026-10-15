package io.jafra.analyzer.recordings.analytics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CorrelationsEngine {

    public Map<String, Object> correlate(Map<String, AnalyticsModels.SubsystemResult> subsystems) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cpuVsWall", cpuVsWall(subsystems));
        out.put("heapVsRss", heapVsRss(subsystems));
        out.put("stackIntersections", stackIntersections(subsystems));
        out.put("allocVsGc", statusOnly("allocation and gc rollups", subsystems.get("allocations"), subsystems.get("gc")));
        out.put("jvmVsMachineCpu", statusOnly("cpu rollup", subsystems.get("cpu"), null));
        out.put("throttleVsLatencyProxy", statusOnly("container and wall_time", subsystems.get("container"), subsystems.get("wall_time")));
        out.put("contentionVsWall", statusOnly("locks and wall_time", subsystems.get("locks"), subsystems.get("wall_time")));
        out.put("compileVsNativeMalloc", compileVsMalloc(subsystems));
        out.put("exceptionsVsGc", statusOnly("exceptions and gc", subsystems.get("exceptions"), subsystems.get("gc")));
        out.put("ioVsBlockedThreads", statusOnly("io and wall_time", subsystems.get("io"), subsystems.get("wall_time")));
        return out;
    }

    private static Map<String, Object> cpuVsWall(Map<String, AnalyticsModels.SubsystemResult> subsystems) {
        AnalyticsModels.SubsystemResult cpu = subsystems.get("cpu");
        AnalyticsModels.SubsystemResult wall = subsystems.get("wall_time");
        if (cpu == null || wall == null || isAbsent(cpu) || isAbsent(wall)) {
            return insufficient();
        }
        Set<String> cpuStacks = stackKeys(cpu);
        Set<String> wallStacks = stackKeys(wall);
        List<String> shared = new ArrayList<>();
        List<String> cpuOnly = new ArrayList<>();
        List<String> wallOnly = new ArrayList<>();
        for (String stack : wallStacks) {
            if (cpuStacks.contains(stack)) {
                shared.add(stack);
            } else {
                wallOnly.add(stack);
            }
        }
        for (String stack : cpuStacks) {
            if (!wallStacks.contains(stack)) {
                cpuOnly.add(stack);
            }
        }
        cap(cpuOnly, 10);
        cap(wallOnly, 10);
        cap(shared, 10);
        Object stateBreakdown = wall.rollup().get("stateBreakdownPercent");
        double offCpuEstimate = 0.0;
        if (stateBreakdown instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (String.valueOf(e.getKey()).contains("SLEEP")) {
                    offCpuEstimate += ((Number) e.getValue()).doubleValue();
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("offCpuFractionEstimatePercent", offCpuEstimate);
        result.put("stacksShared", shared);
        result.put("stacksCpuOnly", cpuOnly);
        result.put("stacksWallOnly", wallOnly);
        return result;
    }

    private static Map<String, Object> heapVsRss(Map<String, AnalyticsModels.SubsystemResult> subsystems) {
        AnalyticsModels.SubsystemResult heap = subsystems.get("heap");
        if (heap == null || isAbsent(heap)) {
            return insufficient();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("heapUsedSlopePerHour", heap.rollup().get("heapUsedSlopePerHour"));
        result.put("rssSlopePerHour", heap.rollup().get("rssSlopePerHour"));
        result.put("divergencePerHour", heap.rollup().get("divergencePerHour"));
        result.put("heapVsRssGapMeanBytes", heap.rollup().get("heapVsRssGapMeanBytes"));
        result.put("divergenceNote", "Positive gap with rising RSS and flat heap suggests native/off-heap growth.");
        return result;
    }

    private static Map<String, Object> stackIntersections(Map<String, AnalyticsModels.SubsystemResult> subsystems) {
        Set<String> cpu = stackKeys(subsystems.get("cpu"));
        Set<String> wall = stackKeys(subsystems.get("wall_time"));
        Set<String> alloc = stackKeys(subsystems.get("allocations"));
        Set<String> allThree = new HashSet<>(cpu);
        allThree.retainAll(wall);
        allThree.retainAll(alloc);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("cpuWallAlloc", capList(allThree, 15));
        return result;
    }

    private static Map<String, Object> compileVsMalloc(Map<String, AnalyticsModels.SubsystemResult> subsystems) {
        AnalyticsModels.SubsystemResult jit = subsystems.get("jit");
        AnalyticsModels.SubsystemResult nativeMem = subsystems.get("native_memory");
        if (jit == null || nativeMem == null || isAbsent(jit) || isAbsent(nativeMem)) {
            return insufficient();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("compilationCount", jit.rollup().get("compilationCount"));
        result.put("compilerNativeMallocBytes", jit.rollup().get("compilerNativeMallocBytes"));
        result.put("mallocBytesTotal", nativeMem.rollup().get("mallocBytesTotal"));
        return result;
    }

    private static Map<String, Object> statusOnly(
            String label, AnalyticsModels.SubsystemResult left, AnalyticsModels.SubsystemResult right) {
        if (left == null || isAbsent(left) || (right != null && isAbsent(right))) {
            return insufficient();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("note", "See subsystem rollups and series for " + label + "; cross-align timestamps manually if needed.");
        return result;
    }

    private static Map<String, Object> insufficient() {
        return Map.of("status", "insufficient_data");
    }

    private static boolean isAbsent(AnalyticsModels.SubsystemResult subsystem) {
        return subsystem.availability().status().equals(AnalyticsModels.AVAIL_ABSENT);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> stackKeys(AnalyticsModels.SubsystemResult subsystem) {
        Set<String> keys = new HashSet<>();
        if (subsystem == null || subsystem.evidence() == null) {
            return keys;
        }
        Object top = subsystem.evidence().get("top");
        if (top instanceof List<?> list) {
            for (Object row : list) {
                if (row instanceof Map<?, ?> map) {
                    Object stack = map.get("stack");
                    if (stack != null) {
                        keys.add(String.valueOf(stack));
                    }
                }
            }
        }
        Object topStacks = subsystem.evidence().get("topStacks");
        if (topStacks instanceof List<?> list) {
            for (Object row : list) {
                if (row instanceof Map<?, ?> map) {
                    Object stack = map.get("stack");
                    if (stack != null) {
                        keys.add(String.valueOf(stack));
                    }
                }
            }
        }
        return keys;
    }

    private static void cap(List<String> list, int max) {
        if (list.size() > max) {
            list.subList(max, list.size()).clear();
        }
    }

    private static List<String> capList(Set<String> set, int max) {
        List<String> list = new ArrayList<>(set);
        cap(list, max);
        return list;
    }
}

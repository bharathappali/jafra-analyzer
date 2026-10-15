package io.jafra.analyzer.recordings.analytics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import io.jafra.analyzer.recordings.stats.Pearson;

@ApplicationScoped
public class CorrelationsEngine {

    public Map<String, Object> correlate(Map<String, AnalyticsModels.SubsystemResult> subsystems) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cpuVsWall", cpuVsWall(subsystems));
        out.put("heapVsRss", heapVsRss(subsystems));
        out.put("stackIntersections", stackIntersections(subsystems));
        out.put("allocVsGc", correlateSeries(
                subsystems.get("allocations"), "allocationBytesPerBucket", false,
                subsystems.get("gc"), "pauseSumMsPerBucket", false));
        out.put("jvmVsMachineCpu", correlateSeries(
                subsystems.get("cpu"), "jvmCpuPercent", false,
                subsystems.get("cpu"), "machineTotalPercent", false));
        out.put("throttleVsLatencyProxy", correlateSeries(
                subsystems.get("container"), "throttleRatio", false,
                subsystems.get("wall_time"), "percentSleeping", false));
        out.put("contentionVsWall", correlateSeries(
                subsystems.get("locks"), "blockedMsPerBucket", false,
                subsystems.get("wall_time"), "wallSamplesPerSec", false));
        out.put("compileVsNativeMalloc", compileVsMalloc(subsystems));
        out.put("exceptionsVsGc", correlateSeries(
                subsystems.get("exceptions"), "throwsPerBucket", false,
                subsystems.get("gc"), "pauseSumMsPerBucket", false));
        out.put("ioVsBlockedThreads", correlateSeries(
                subsystems.get("io"), "ioDurationMsPerBucket", false,
                subsystems.get("wall_time"), "wallSamplesPerSec", false));
        out.put("throttleVsRunnableWithoutCpu", throttleVsRunnableWithoutCpu(subsystems));
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

    private static Map<String, Object> throttleVsRunnableWithoutCpu(
            Map<String, AnalyticsModels.SubsystemResult> subsystems) {
        List<AnalyticsModels.SeriesPoint> throttle = points(subsystems.get("container"), "throttleRatio");
        List<AnalyticsModels.SeriesPoint> runnable = points(subsystems.get("wall_time"), "runnableSamplesPerBucket");
        List<AnalyticsModels.SeriesPoint> cpu = points(subsystems.get("cpu"), "cpuSampleRatePerSec");
        if (throttle.isEmpty() || runnable.isEmpty() || cpu.isEmpty()) {
            return insufficient(0);
        }
        Map<String, Double> runnableByAt = valuesByAt(runnable, false);
        Map<String, Double> cpuByAt = valuesByAt(cpu, true);
        List<Double> left = new ArrayList<>();
        List<Double> right = new ArrayList<>();
        for (AnalyticsModels.SeriesPoint point : throttle) {
            if (!runnableByAt.containsKey(point.at()) || !cpuByAt.containsKey(point.at())) {
                continue;
            }
            left.add(number(point, false));
            Double runnableValue = runnableByAt.get(point.at());
            Double cpuSamples = cpuByAt.get(point.at());
            if (runnableValue == null && cpuSamples == null) {
                right.add(null);
            } else {
                double runnableSamples = runnableValue == null ? 0.0 : runnableValue;
                double samples = cpuSamples == null ? 0.0 : cpuSamples;
                right.add(Math.max(0.0, runnableSamples - samples));
            }
        }
        return Pearson.bestLag(left, right);
    }

    private static Map<String, Object> correlateSeries(
            AnalyticsModels.SubsystemResult leftSubsystem,
            String leftId,
            boolean leftCount,
            AnalyticsModels.SubsystemResult rightSubsystem,
            String rightId,
            boolean rightCount) {
        List<AnalyticsModels.SeriesPoint> leftPoints = points(leftSubsystem, leftId);
        List<AnalyticsModels.SeriesPoint> rightPoints = points(rightSubsystem, rightId);
        if (leftPoints.isEmpty() || rightPoints.isEmpty()) {
            return insufficient(0);
        }
        Map<String, Double> rightByAt = valuesByAt(rightPoints, rightCount);
        List<Double> left = new ArrayList<>();
        List<Double> right = new ArrayList<>();
        for (AnalyticsModels.SeriesPoint point : leftPoints) {
            if (!rightByAt.containsKey(point.at())) {
                continue;
            }
            left.add(number(point, leftCount));
            right.add(rightByAt.get(point.at()));
        }
        return Pearson.bestLag(left, right);
    }

    private static List<AnalyticsModels.SeriesPoint> points(AnalyticsModels.SubsystemResult subsystem, String seriesId) {
        if (subsystem == null || subsystem.series() == null) {
            return List.of();
        }
        for (AnalyticsModels.MetricSeries series : subsystem.series()) {
            if (seriesId.equals(series.id()) && series.points() != null) {
                return series.points();
            }
        }
        return List.of();
    }

    private static Map<String, Double> valuesByAt(List<AnalyticsModels.SeriesPoint> points, boolean useCount) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (AnalyticsModels.SeriesPoint point : points) {
            values.put(point.at(), number(point, useCount));
        }
        return values;
    }

    private static Double number(AnalyticsModels.SeriesPoint point, boolean useCount) {
        Number raw = useCount ? point.count() : point.value();
        if (raw == null) {
            return null;
        }
        double value = raw.doubleValue();
        return Double.isNaN(value) ? null : value;
    }

    private static Map<String, Object> insufficient(int n) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "insufficient_data");
        result.put("n", n);
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
                    addMatchKey(keys, map);
                }
            }
        }
        Object topStacks = subsystem.evidence().get("topStacks");
        if (topStacks instanceof List<?> list) {
            for (Object row : list) {
                if (row instanceof Map<?, ?> map) {
                    addMatchKey(keys, map);
                }
            }
        }
        return keys;
    }

    private static void addMatchKey(Set<String> keys, Map<?, ?> map) {
        Object matchKey = map.get("matchKey");
        if (matchKey != null) {
            keys.add(String.valueOf(matchKey));
            return;
        }
        Object stack = map.get("stack");
        if (stack != null) {
            keys.add(String.valueOf(stack));
        }
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

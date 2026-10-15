package io.jafra.analyzer.recordings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.ItemFilters;

import io.jafra.analyzer.recordings.analytics.AnalyticsModels;
import io.jafra.analyzer.recordings.stats.Percentiles;

/** Shared JFR analytics helpers for aggregation and analytics APIs. */
public final class JfrAnalyticsSupport {
    public static final long CONTENTION_MIN_NS = 20_000_000L;

    /** Top-level pauses only. Level events are nested sub-phases and must not enter pause totals. */
    public static final List<String> GC_PAUSE_TYPES = List.of("jdk.GCPhasePause");

    public static final List<String> GC_PHASE_LEVEL_TYPES = List.of(
            "jdk.GCPhasePauseLevel1",
            "jdk.GCPhasePauseLevel2",
            "jdk.GCPhasePauseLevel3",
            "jdk.GCPhasePauseLevel4");

    public static final List<String> YOUNG_GC_TYPES = List.of("jdk.YoungGarbageCollection", "jdk.G1GarbageCollection");

    public static final List<String> ALLOCATION_TYPES = List.of(
            "jdk.ObjectAllocationInNewTLAB",
            "jdk.ObjectAllocationOutsideTLAB",
            "jdk.ObjectAllocationSample",
            "profiler.ObjectAllocationInNewTLAB",
            "profiler.ObjectAllocationOutsideTLAB");

    public static final List<String> CPU_SAMPLE_TYPES = List.of(
            "jdk.ExecutionSample", "jdk.NativeMethodSample", "profiler.ExecutionSample");

    public static final List<String> WALL_SAMPLE_TYPES = List.of("profiler.WallClockSample", "profiler.ExecutionSample");

    public static final List<String> CONTENTION_TYPES = List.of(
            "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark", "profiler.NativeLock");

    public static final List<String> EXCEPTION_TYPES = List.of("jdk.JavaExceptionThrow", "jdk.JavaErrorThrow");

    private JfrAnalyticsSupport() {}

    public static List<String> present(List<String> candidates, Set<String> available) {
        List<String> types = new ArrayList<>();
        for (String typeId : candidates) {
            if (available.contains(typeId)) {
                types.add(typeId);
            }
        }
        return types;
    }

    public static AnalyticsModels.Availability availability(List<String> expected, Set<String> available) {
        List<String> seen = new ArrayList<>();
        for (String type : expected) {
            if (available.contains(type)) {
                seen.add(type);
            }
        }
        String status;
        if (seen.isEmpty()) {
            status = AnalyticsModels.AVAIL_ABSENT;
        } else if (seen.size() == expected.size()) {
            status = AnalyticsModels.AVAIL_PRESENT;
        } else {
            status = AnalyticsModels.AVAIL_PARTIAL;
        }
        return new AnalyticsModels.Availability(status, expected, seen);
    }

    public static void collectPauseDurations(IItemCollection events, String typeId, List<Long> pauseNs) {
        for (IItemIterable iterable : events.apply(ItemFilters.type(typeId))) {
            for (IItem item : iterable) {
                Long duration = JfrItemAccess.durationNs(item);
                if (duration != null && duration > 0) {
                    pauseNs.add(duration);
                }
            }
        }
    }

    public static Map<String, Object> pauseStats(List<Long> pauseNs) {
        Map<String, Object> stats = new LinkedHashMap<>();
        if (pauseNs.isEmpty()) {
            stats.put("count", 0L);
            stats.put("sumMs", 0.0);
            stats.put("maxMs", 0.0);
            stats.put("p50Ms", 0.0);
            stats.put("p90Ms", 0.0);
            stats.put("p95Ms", 0.0);
            stats.put("p99Ms", 0.0);
            return stats;
        }
        long sum = 0L;
        List<Double> pauseMs = new ArrayList<>(pauseNs.size());
        for (long value : pauseNs) {
            sum += value;
            pauseMs.add(value / 1_000_000.0);
        }
        Percentiles.Summary summary = Percentiles.summarize(pauseMs).orElseThrow();
        stats.put("count", (long) pauseNs.size());
        stats.put("sumMs", sum / 1_000_000.0);
        stats.put("maxMs", summary.max());
        stats.put("p50Ms", summary.p50());
        stats.put("p90Ms", summary.p90());
        stats.put("p95Ms", summary.p95());
        stats.put("p99Ms", summary.p99());
        return stats;
    }

    /**
     * Level1–4 phase events grouped by phase name. These rows are a breakdown only and are excluded
     * from {@link #pauseStats}.
     */
    public static List<Map<String, Object>> gcPhaseBreakdown(IItemCollection events, Set<String> available) {
        Map<String, PhaseAccumulator> byKey = new LinkedHashMap<>();
        for (String typeId : present(GC_PHASE_LEVEL_TYPES, available)) {
            int level = phaseLevel(typeId);
            for (IItemIterable iterable : events.apply(ItemFilters.type(typeId))) {
                for (IItem item : iterable) {
                    Long duration = JfrItemAccess.durationNs(item);
                    if (duration == null || duration <= 0) {
                        continue;
                    }
                    String rawName = JfrItemAccess.text(item, "name");
                    String phaseName = rawName == null || rawName.isBlank() ? typeId : rawName;
                    String key = level + "\0" + phaseName;
                    PhaseAccumulator acc =
                            byKey.computeIfAbsent(key, ignored -> new PhaseAccumulator(phaseName, level));
                    acc.count++;
                    acc.sumNs += duration;
                    if (duration > acc.maxNs) {
                        acc.maxNs = duration;
                    }
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PhaseAccumulator acc : byKey.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", acc.name);
            row.put("level", acc.level);
            row.put("count", acc.count);
            row.put("sumMs", acc.sumNs / 1_000_000.0);
            row.put("maxMs", acc.maxNs / 1_000_000.0);
            rows.add(row);
        }
        return rows;
    }

    private static int phaseLevel(String typeId) {
        if (typeId.endsWith("Level1")) {
            return 1;
        }
        if (typeId.endsWith("Level2")) {
            return 2;
        }
        if (typeId.endsWith("Level3")) {
            return 3;
        }
        if (typeId.endsWith("Level4")) {
            return 4;
        }
        return 0;
    }

    public static long countEvents(IItemCollection events, String typeId) {
        long count = 0L;
        for (IItemIterable iterable : events.apply(ItemFilters.type(typeId))) {
            for (IItem ignored : iterable) {
                count++;
            }
        }
        return count;
    }

    public static void rankStacks(
            IItemCollection events, List<String> typeIds, Map<String, MutableRank> byStack, long weightPerSample) {
        for (String typeId : typeIds) {
            for (IItemIterable iterable : events.apply(ItemFilters.type(typeId))) {
                for (IItem item : iterable) {
                    StackIds.Identity identity = JfrItemAccess.stackIdentity(item);
                    String stack = JfrItemAccess.stackTrace(item);
                    if (identity == null || stack == null) {
                        continue;
                    }
                    bump(byStack, "stack", stack, stack, identity, weightPerSample, 0L);
                }
            }
        }
    }

    public static void rankAllocations(
            IItemCollection events, List<String> typeIds, Map<String, MutableRank> byClass, Map<String, MutableRank> byStack) {
        for (String typeId : typeIds) {
            for (IItemIterable iterable : events.apply(ItemFilters.type(typeId))) {
                for (IItem item : iterable) {
                    long bytes = JfrItemAccess.allocationBytes(item);
                    String clazz = JfrItemAccess.text(item, "objectClass", "class");
                    if (clazz == null) {
                        clazz = "(unknown)";
                    }
                    bump(byClass, "class", clazz, null, bytes, 0L);
                    StackIds.Identity identity = JfrItemAccess.stackIdentity(item);
                    String stack = JfrItemAccess.stackTrace(item);
                    if (identity != null && stack != null) {
                        bump(byStack, "stack", stack, stack, identity, bytes, 0L);
                    }
                }
            }
        }
    }

    public static List<JfrAggregationService.RankedEntry> topRanks(Map<String, MutableRank> ranks, int top) {
        List<MutableRank> sorted = new ArrayList<>(ranks.values());
        sorted.sort(Comparator.comparingLong((MutableRank rank) -> rank.bytes + rank.count + rank.totalNs)
                .reversed());
        List<JfrAggregationService.RankedEntry> entries = new ArrayList<>();
        for (int index = 0; index < sorted.size() && index < top; index++) {
            entries.add(sorted.get(index).toEntry());
        }
        return entries;
    }

    public static double linearSlopePerHour(List<long[]> points) {
        if (points.size() < 2) {
            return 0.0;
        }
        double sumX = 0.0;
        double sumY = 0.0;
        double sumXY = 0.0;
        double sumXX = 0.0;
        int n = points.size();
        for (long[] point : points) {
            double x = point[0] / 1_000_000_000.0;
            double y = point[1];
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumXX += x * x;
        }
        double denom = n * sumXX - sumX * sumX;
        if (denom == 0.0) {
            return 0.0;
        }
        return ((n * sumXY - sumX * sumY) / denom) * 3600.0;
    }

    public static Map<String, Object> numericSummary(List<Double> values) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (values.isEmpty()) {
            map.put("count", 0L);
            return map;
        }
        double sum = 0.0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double v : values) {
            sum += v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        Percentiles.Summary summary = Percentiles.summarize(values).orElseThrow();
        map.put("count", (long) values.size());
        map.put("mean", sum / values.size());
        map.put("min", min);
        map.put("max", summary.max());
        map.put("p50", summary.p50());
        map.put("p90", summary.p90());
        map.put("p95", summary.p95());
        map.put("p99", summary.p99());
        return map;
    }

    public static void bump(
            Map<String, MutableRank> ranks,
            String group,
            String key,
            String stack,
            long bytes,
            long durationNs) {
        bump(ranks, group, key, stack, null, bytes, durationNs);
    }

    public static void bump(
            Map<String, MutableRank> ranks,
            String group,
            String key,
            String stack,
            StackIds.Identity identity,
            long bytes,
            long durationNs) {
        String mergeKey = identity == null ? key : identity.stackId();
        MutableRank rank = ranks.computeIfAbsent(
                group + "\0" + mergeKey, ignored -> new MutableRank(group, key, stack, identity));
        rank.count++;
        rank.bytes += bytes;
        rank.totalNs += durationNs;
        if (rank.stack == null && stack != null) {
            rank.stack = stack;
        }
        if (rank.identity == null && identity != null) {
            rank.identity = identity;
        }
    }

    public static final class MutableRank {
        public final String group;
        public final String key;
        public String stack;
        public StackIds.Identity identity;
        public long count;
        public long bytes;
        public long totalNs;

        MutableRank(String group, String key, String stack, StackIds.Identity identity) {
            this.group = group;
            this.key = key;
            this.stack = stack;
            this.identity = identity;
        }

        JfrAggregationService.RankedEntry toEntry() {
            String stackId = identity == null ? null : identity.stackId();
            String leaf = identity == null ? null : identity.leaf();
            String topApp = identity == null ? null : identity.topAppFrame();
            Integer depth = identity == null ? null : identity.depth();
            String matchKey = identity == null ? null : identity.matchKey();
            return new JfrAggregationService.RankedEntry(
                    group, key, stack, count, bytes, totalNs, stackId, leaf, topApp, depth, matchKey);
        }
    }

    private static final class PhaseAccumulator {
        final String name;
        final int level;
        long count;
        long sumNs;
        long maxNs;

        PhaseAccumulator(String name, int level) {
            this.name = name;
            this.level = level;
        }
    }
}

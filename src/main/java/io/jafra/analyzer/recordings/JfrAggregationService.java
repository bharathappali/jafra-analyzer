package io.jafra.analyzer.recordings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.ItemFilters;

@ApplicationScoped
public class JfrAggregationService {
    private static final int DEFAULT_TOP = 20;
    private static final int MAX_TOP = 100;
    private static final long DEFAULT_CONTENTION_MIN_NS = 20_000_000L;

    private static final Set<String> KINDS = Set.of(
            "gc-pauses", "trends", "allocations", "samples", "contention", "exceptions");

    private static final List<String> YOUNG_GC_TYPES = List.of("jdk.YoungGarbageCollection", "jdk.G1GarbageCollection");

    private static final List<String> ALLOCATION_TYPES = List.of(
            "jdk.ObjectAllocationInNewTLAB",
            "jdk.ObjectAllocationOutsideTLAB",
            "jdk.ObjectAllocationSample",
            "profiler.ObjectAllocationInNewTLAB",
            "profiler.ObjectAllocationOutsideTLAB");

    private static final List<String> CPU_SAMPLE_TYPES = List.of("jdk.ExecutionSample", "jdk.NativeMethodSample");

    private static final List<String> WALL_SAMPLE_TYPES = List.of("profiler.WallClockSample", "profiler.ExecutionSample");

    private static final List<String> CONTENTION_TYPES = List.of(
            "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark", "profiler.NativeLock");

    private static final List<String> EXCEPTION_TYPES = List.of("jdk.JavaExceptionThrow");

    public AggregationDocument aggregate(
            RecordingCatalog.WindowSelection selection, String kind, Integer limit, String minDuration, String flavor)
            throws IOException {
        String normalized = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        if (!KINDS.contains(normalized)) {
            throw new IllegalArgumentException(
                    "kind must be one of: gc-pauses, trends, allocations, samples, contention, exceptions");
        }
        int top = clampTop(limit);
        IItemCollection events = JfrEventSummarizer.loadEvents(selection);
        Set<String> available = JfrEventSummarizer.discoverTypes(events).keySet();
        return switch (normalized) {
            case "gc-pauses" -> gcPauses(selection, events, available);
            case "trends" -> trends(selection, events, available);
            case "allocations" -> allocations(selection, events, available, top);
            case "samples" -> samples(selection, events, available, top, flavor);
            case "contention" -> contention(selection, events, available, top, minDuration);
            case "exceptions" -> exceptions(selection, events, available, top);
            default -> throw new IllegalArgumentException("unsupported kind: " + kind);
        };
    }

    private AggregationDocument gcPauses(
            RecordingCatalog.WindowSelection selection, IItemCollection events, Set<String> available) {
        List<Long> pauseNs = new ArrayList<>();
        for (String typeId : JfrAnalyticsSupport.present(JfrAnalyticsSupport.GC_PAUSE_TYPES, available)) {
            JfrAnalyticsSupport.collectPauseDurations(events, typeId, pauseNs);
        }
        long youngGcCount = 0L;
        for (String typeId : present(YOUNG_GC_TYPES, available)) {
            youngGcCount += JfrAnalyticsSupport.countEvents(events, typeId);
        }
        Map<String, Object> stats = JfrAnalyticsSupport.pauseStats(pauseNs);
        stats.put("youngGcCount", youngGcCount);
        stats.put("phaseBreakdown", JfrAnalyticsSupport.gcPhaseBreakdown(events, available));
        return new AggregationDocument(AnalysisEnvelope.from(selection), "gc-pauses", stats, List.of(), List.of());
    }

    private AggregationDocument trends(
            RecordingCatalog.WindowSelection selection, IItemCollection events, Set<String> available) {
        List<TrendSeries> series = new ArrayList<>();
        if (available.contains("jdk.GCHeapSummary")) {
            series.add(trendSeries(events, "jdk.GCHeapSummary", "heapUsed"));
        }
        if (available.contains("jdk.MetaspaceSummary")) {
            TrendSeries metaspace = trendSeries(events, "jdk.MetaspaceSummary", "used");
            if (metaspace.points().isEmpty()) {
                metaspace = trendSeries(events, "jdk.MetaspaceSummary", "metaspaceUsed");
            }
            if (!metaspace.points().isEmpty()) {
                series.add(metaspace);
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        for (TrendSeries trend : series) {
            stats.put(trend.name() + "SlopePerHour", trend.slopePerHour());
        }
        return new AggregationDocument(AnalysisEnvelope.from(selection), "trends", stats, series, List.of());
    }

    private AggregationDocument allocations(
            RecordingCatalog.WindowSelection selection,
            IItemCollection events,
            Set<String> available,
            int top) {
        Map<String, MutableRank> byClass = new LinkedHashMap<>();
        Map<String, MutableRank> byStack = new LinkedHashMap<>();
        long eventCount = 0L;
        for (String typeId : present(ALLOCATION_TYPES, available)) {
            for (IItemIterable iterable : events.apply(ItemFilters.type(typeId))) {
                for (IItem item : iterable) {
                    long bytes = JfrItemAccess.allocationBytes(item);
                    eventCount++;
                    String clazz = JfrItemAccess.text(item, "objectClass", "class");
                    if (clazz == null) {
                        clazz = "(unknown)";
                    }
                    bump(byClass, "class", clazz, null, bytes, 0L);
                    String stack = JfrItemAccess.stackTrace(item);
                    if (stack != null) {
                        bump(byStack, "stack", stack, stack, bytes, 0L);
                    }
                }
            }
        }
        List<RankedEntry> ranked = new ArrayList<>();
        ranked.addAll(topEntries(byClass, top));
        ranked.addAll(topEntries(byStack, top));
        Map<String, Object> stats = Map.of("eventCount", eventCount);
        return new AggregationDocument(AnalysisEnvelope.from(selection), "allocations", stats, List.of(), ranked);
    }

    private AggregationDocument samples(
            RecordingCatalog.WindowSelection selection,
            IItemCollection events,
            Set<String> available,
            int top,
            String flavor) {
        String mode = flavor == null || flavor.isBlank() ? "all" : flavor.trim().toLowerCase(Locale.ROOT);
        List<String> types = sampleTypes(mode, available);
        Map<String, MutableRank> byStack = new LinkedHashMap<>();
        long total = 0L;
        for (String typeId : types) {
            for (IItemIterable iterable : events.apply(ItemFilters.type(typeId))) {
                for (IItem item : iterable) {
                    total++;
                    String stack = JfrItemAccess.stackTrace(item);
                    if (stack == null) {
                        continue;
                    }
                    bump(byStack, "stack", stack, stack, 0L, 0L);
                }
            }
        }
        List<RankedEntry> ranked = topEntries(byStack, top);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("sampleCount", total);
        stats.put("flavor", mode);
        return new AggregationDocument(AnalysisEnvelope.from(selection), "samples", stats, List.of(), ranked);
    }

    private AggregationDocument contention(
            RecordingCatalog.WindowSelection selection,
            IItemCollection events,
            Set<String> available,
            int top,
            String minDuration) {
        Long minNs = JfrDuration.parseNanos(minDuration);
        if (minNs == null) {
            minNs = DEFAULT_CONTENTION_MIN_NS;
        }
        Map<String, MutableRank> byMonitor = new LinkedHashMap<>();
        long eventCount = 0L;
        for (String typeId : present(CONTENTION_TYPES, available)) {
            for (IItemIterable iterable : events.apply(ItemFilters.type(typeId))) {
                for (IItem item : iterable) {
                    Long duration = JfrItemAccess.durationNs(item);
                    if (duration == null || duration < minNs) {
                        continue;
                    }
                    eventCount++;
                    String monitor = JfrItemAccess.text(item, "monitorClass", "lockClass", "class");
                    if (monitor == null) {
                        monitor = typeId;
                    }
                    String stack = JfrItemAccess.stackTrace(item);
                    bump(byMonitor, "monitor", monitor, stack, 0L, duration);
                }
            }
        }
        List<RankedEntry> ranked = topEntries(byMonitor, top);
        Map<String, Object> stats = Map.of("eventCount", eventCount, "minDurationNs", minNs);
        return new AggregationDocument(AnalysisEnvelope.from(selection), "contention", stats, List.of(), ranked);
    }

    private AggregationDocument exceptions(
            RecordingCatalog.WindowSelection selection,
            IItemCollection events,
            Set<String> available,
            int top) {
        Map<String, MutableRank> byClass = new LinkedHashMap<>();
        Map<String, MutableRank> byStack = new LinkedHashMap<>();
        long eventCount = 0L;
        for (String typeId : present(EXCEPTION_TYPES, available)) {
            for (IItemIterable iterable : events.apply(ItemFilters.type(typeId))) {
                for (IItem item : iterable) {
                    eventCount++;
                    String clazz = JfrItemAccess.text(item, "thrownClass", "exceptionClass", "class");
                    if (clazz == null) {
                        clazz = "(unknown)";
                    }
                    bump(byClass, "class", clazz, null, 0L, 0L);
                    String stack = JfrItemAccess.stackTrace(item);
                    if (stack != null) {
                        bump(byStack, "stack", stack, stack, 0L, 0L);
                    }
                }
            }
        }
        List<RankedEntry> ranked = new ArrayList<>();
        ranked.addAll(topEntries(byClass, top));
        ranked.addAll(topEntries(byStack, top));
        Map<String, Object> stats = Map.of("eventCount", eventCount);
        return new AggregationDocument(AnalysisEnvelope.from(selection), "exceptions", stats, List.of(), ranked);
    }

    private static List<String> sampleTypes(String mode, Set<String> available) {
        if ("cpu".equals(mode)) {
            return present(CPU_SAMPLE_TYPES, available);
        }
        if ("wall".equals(mode)) {
            return present(WALL_SAMPLE_TYPES, available);
        }
        List<String> types = new ArrayList<>();
        types.addAll(present(CPU_SAMPLE_TYPES, available));
        types.addAll(present(WALL_SAMPLE_TYPES, available));
        return types;
    }

    private static TrendSeries trendSeries(IItemCollection events, String typeId, String field) {
        List<TrendPoint> points = new ArrayList<>();
        for (IItemIterable iterable : events.apply(ItemFilters.type(typeId))) {
            for (IItem item : iterable) {
                Long value = JfrItemAccess.quantityLong(item, field);
                if (value == null) {
                    continue;
                }
                long timeNs = JfrItemAccess.startTimeNs(item);
                points.add(new TrendPoint(timeNs, value));
            }
        }
        points.sort(Comparator.comparingLong(TrendPoint::timeNs));
        double slopePerHour = linearSlopePerHour(points);
        return new TrendSeries(typeId, field, List.copyOf(points), slopePerHour);
    }

    private static double linearSlopePerHour(List<TrendPoint> points) {
        if (points.size() < 2) {
            return 0.0;
        }
        double sumX = 0.0;
        double sumY = 0.0;
        double sumXY = 0.0;
        double sumXX = 0.0;
        int n = points.size();
        for (TrendPoint point : points) {
            double x = point.timeNs() / 1_000_000_000.0;
            double y = point.value();
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumXX += x * x;
        }
        double denom = n * sumXX - sumX * sumX;
        if (denom == 0.0) {
            return 0.0;
        }
        double slopePerSecond = (n * sumXY - sumX * sumY) / denom;
        return slopePerSecond * 3600.0;
    }

    private static List<String> present(List<String> candidates, Set<String> available) {
        List<String> types = new ArrayList<>();
        for (String typeId : candidates) {
            if (available.contains(typeId)) {
                types.add(typeId);
            }
        }
        return types;
    }

    private static void bump(
            Map<String, MutableRank> ranks,
            String group,
            String key,
            String stack,
            long bytes,
            long durationNs) {
        MutableRank rank = ranks.computeIfAbsent(group + "\0" + key, ignored -> new MutableRank(group, key, stack));
        rank.count++;
        rank.bytes += bytes;
        rank.totalNs += durationNs;
        if (rank.stack == null && stack != null) {
            rank.stack = stack;
        }
    }

    private static List<RankedEntry> topEntries(Map<String, MutableRank> ranks, int top) {
        List<MutableRank> sorted = new ArrayList<>(ranks.values());
        sorted.sort(Comparator.comparingLong((MutableRank rank) -> rank.bytes + rank.count + rank.totalNs)
                .reversed());
        List<RankedEntry> entries = new ArrayList<>();
        for (int index = 0; index < sorted.size() && index < top; index++) {
            entries.add(sorted.get(index).toEntry());
        }
        return entries;
    }

    private static int clampTop(Integer limit) {
        if (limit == null) {
            return DEFAULT_TOP;
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (limit > MAX_TOP) {
            throw new IllegalArgumentException("limit exceeds maximum of " + MAX_TOP);
        }
        return limit;
    }

    private static final class MutableRank {
        final String group;
        final String key;
        String stack;
        long count;
        long bytes;
        long totalNs;

        MutableRank(String group, String key, String stack) {
            this.group = group;
            this.key = key;
            this.stack = stack;
        }

        RankedEntry toEntry() {
            return new RankedEntry(group, key, stack, count, bytes, totalNs);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AggregationDocument(
            AnalysisEnvelope context,
            String kind,
            Map<String, Object> stats,
            List<TrendSeries> trends,
            List<RankedEntry> top) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TrendSeries(String name, String field, List<TrendPoint> points, double slopePerHour) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TrendPoint(long timeNs, long value) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RankedEntry(String group, String key, String stack, long count, long bytes, long totalNs) {}
}

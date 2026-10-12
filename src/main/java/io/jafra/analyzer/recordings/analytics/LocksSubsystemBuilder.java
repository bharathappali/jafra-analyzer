package io.jafra.analyzer.recordings.analytics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.ItemFilters;

import io.jafra.analyzer.recordings.JfrAnalyticsSupport;
import io.jafra.analyzer.recordings.JfrItemAccess;
import io.jafra.analyzer.recordings.TimeBucketGrid;

final class LocksSubsystemBuilder extends AnalyticsSubsystemSupport {
    private static final List<String> EXPECTED = List.of(
            "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark", "profiler.NativeLock");

    @Override
    public String id() {
        return "locks";
    }

    @Override
    public AnalyticsModels.SubsystemResult build(
            IItemCollection events,
            Set<String> available,
            TimeBucketGrid grid,
            AnalyticsModels.AnalyticsParams params,
            List<AnalyticsModels.Marker> markers) {
        var availability = avail(EXPECTED, available);
        List<String> types = JfrAnalyticsSupport.present(JfrAnalyticsSupport.CONTENTION_TYPES, available);
        if (types.isEmpty()) {
            return absent(id(), EXPECTED);
        }
        Map<String, JfrAnalyticsSupport.MutableRank> monitors = new LinkedHashMap<>();
        long eventCount = 0L;
        long blockedNs = 0L;
        List<AnalyticsModels.MetricSeries> series = new ArrayList<>();
        int n = grid == null ? 0 : grid.bucketCount();
        int[] eventBuckets = n > 0 ? BucketAggregators.newCountBuckets(n) : null;
        double[] blockedMs = n > 0 ? BucketAggregators.newSumBuckets(n) : null;
        double[] maxMs = n > 0 ? BucketAggregators.newMaxBuckets(n) : null;
        int[] counts = n > 0 ? BucketAggregators.newCountBuckets(n) : null;
        for (String typeId : types) {
            for (IItemIterable iterable : events.apply(ItemFilters.type(typeId))) {
                for (IItem item : iterable) {
                    Long duration = JfrItemAccess.durationNs(item);
                    if (duration == null || duration < JfrAnalyticsSupport.CONTENTION_MIN_NS) {
                        continue;
                    }
                    eventCount++;
                    blockedNs += duration;
                    String monitor = JfrItemAccess.text(item, "monitorClass", "lockClass", "class");
                    if (monitor == null) {
                        monitor = typeId;
                    }
                    String stack = JfrItemAccess.stackTrace(item);
                    JfrAnalyticsSupport.bump(monitors, "monitor", monitor, stack, 0L, duration);
                    long t = JfrItemAccess.startTimeNs(item);
                    if (eventBuckets != null) {
                        double ms = duration / 1_000_000.0;
                        BucketAggregators.addDurationMs(grid, counts, blockedMs, maxMs, t, ms);
                        BucketAggregators.incrementCount(grid, eventBuckets, t);
                    }
                }
            }
        }
        if (eventBuckets != null && timeseries(params)) {
            series.add(new AnalyticsModels.MetricSeries(
                    "contentionEventsPerBucket",
                    "Contention events",
                    "count",
                    "Lock/monitor events above duration threshold per bucket.",
                    BucketAggregators.countSeries(grid, eventBuckets, 0)));
            series.add(new AnalyticsModels.MetricSeries(
                    "blockedMsPerBucket",
                    "Blocked time",
                    "ms",
                    "Sum of contention durations per bucket.",
                    BucketAggregators.durationSumSeries(grid, counts, blockedMs, maxMs)));
        }
        Map<String, Object> rollup = new LinkedHashMap<>();
        rollup.put("contentionEventCount", eventCount);
        rollup.put("totalBlockedMs", blockedNs / 1_000_000.0);
        rollup.put("minDurationMs", JfrAnalyticsSupport.CONTENTION_MIN_NS / 1_000_000.0);
        return new AnalyticsModels.SubsystemResult(
                id(),
                availability,
                rollup,
                seriesOrNull(timeseries(params), series),
                evidenceFromRanks(JfrAnalyticsSupport.topRanks(monitors, top(params))),
                List.of("Compare with wall_time sleeping/blocked stacks."));
    }
}

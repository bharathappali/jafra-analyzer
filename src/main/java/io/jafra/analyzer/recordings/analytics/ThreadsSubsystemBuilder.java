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

final class ThreadsSubsystemBuilder extends AnalyticsSubsystemSupport {
    private static final List<String> EXPECTED = List.of(
            "jdk.JavaThreadStatistics", "jdk.ThreadStart", "jdk.ThreadAllocationStatistics");

    @Override
    public String id() {
        return "threads";
    }

    @Override
    public AnalyticsModels.SubsystemResult build(
            IItemCollection events,
            Set<String> available,
            TimeBucketGrid grid,
            AnalyticsModels.AnalyticsParams params,
            List<AnalyticsModels.Marker> markers) {
        var availability = avail(EXPECTED, available);
        if (availability.status().equals(AnalyticsModels.AVAIL_ABSENT)) {
            return absent(id(), EXPECTED);
        }
        List<Double> live = new ArrayList<>();
        List<Double> ctxSwitch = new ArrayList<>();
        List<AnalyticsModels.MetricSeries> series = new ArrayList<>();
        int n = grid == null ? 0 : grid.bucketCount();
        int[] liveCounts = n > 0 ? BucketAggregators.newGaugeCounts(n) : null;
        double[] liveSum = n > 0 ? BucketAggregators.newGaugeMeans(n) : null;
        double[] liveLast = n > 0 ? BucketAggregators.newGaugeLast(n) : null;
        if (available.contains("jdk.JavaThreadStatistics")) {
            for (IItemIterable iterable : events.apply(ItemFilters.type("jdk.JavaThreadStatistics"))) {
                for (IItem item : iterable) {
                    Double count = readDouble(item, "activeCount");
                    if (count == null) {
                        count = readDouble(item, "threadCount");
                    }
                    if (count != null) {
                        live.add(count);
                        if (liveCounts != null) {
                            BucketAggregators.addGauge(
                                    grid, liveCounts, liveSum, liveLast, JfrItemAccess.startTimeNs(item), count);
                        }
                    }
                }
            }
        }
        if (available.contains("jdk.ThreadContextSwitchRate")) {
            for (IItemIterable iterable : events.apply(ItemFilters.type("jdk.ThreadContextSwitchRate"))) {
                for (IItem item : iterable) {
                    Double rate = readDouble(item, "switchRate");
                    if (rate != null) {
                        ctxSwitch.add(rate);
                    }
                }
            }
        }
        if (liveCounts != null && timeseries(params)) {
            series.add(new AnalyticsModels.MetricSeries(
                    "liveThreadCount",
                    "Live threads",
                    "count",
                    "Rising trend may indicate pool leaks or traffic growth.",
                    BucketAggregators.gaugeSeries(grid, liveCounts, liveSum, liveLast)));
        }
        long starts = JfrAnalyticsSupport.countEvents(events, "jdk.ThreadStart");
        long ends = JfrAnalyticsSupport.countEvents(events, "jdk.ThreadEnd");
        Map<String, Object> rollup = new LinkedHashMap<>();
        rollup.put("liveThreads", JfrAnalyticsSupport.numericSummary(live));
        rollup.put("contextSwitchRate", JfrAnalyticsSupport.numericSummary(ctxSwitch));
        rollup.put("threadStartCount", starts);
        rollup.put("threadEndCount", ends);
        return new AnalyticsModels.SubsystemResult(
                id(),
                availability,
                rollup,
                seriesOrNull(timeseries(params), series),
                Map.of(),
                List.of("Pair with locks and wall_time for pool exhaustion."));
    }
}

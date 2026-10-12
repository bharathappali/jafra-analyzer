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

final class WallTimeSubsystemBuilder extends AnalyticsSubsystemSupport {
    private static final List<String> EXPECTED = List.of("profiler.WallClockSample");

    @Override
    public String id() {
        return "wall_time";
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
        Map<String, Long> stateCounts = new LinkedHashMap<>();
        Map<String, Long> threadCounts = new LinkedHashMap<>();
        Map<String, JfrAnalyticsSupport.MutableRank> stacks = new LinkedHashMap<>();
        long total = 0L;
        List<AnalyticsModels.MetricSeries> series = new ArrayList<>();
        int[] sampleBuckets = null;
        int[] sleepBuckets = null;
        int n = grid == null ? 0 : grid.bucketCount();
        if (timeseries(params) && grid != null) {
            sampleBuckets = BucketAggregators.newCountBuckets(n);
            sleepBuckets = BucketAggregators.newCountBuckets(n);
        }
        for (IItemIterable iterable : events.apply(ItemFilters.type("profiler.WallClockSample"))) {
            for (IItem item : iterable) {
                total++;
                String state = JfrItemAccess.text(item, "state");
                if (state != null) {
                    stateCounts.merge(state, 1L, Long::sum);
                }
                String thread = JfrItemAccess.text(item, "sampledThread");
                if (thread != null) {
                    threadCounts.merge(thread, 1L, Long::sum);
                }
                String stack = JfrItemAccess.stackTrace(item);
                if (stack != null) {
                    JfrAnalyticsSupport.bump(stacks, "stack", stack, stack, 1L, 0L);
                }
                long t = JfrItemAccess.startTimeNs(item);
                if (sampleBuckets != null) {
                    BucketAggregators.incrementCount(grid, sampleBuckets, t);
                    if (state != null && state.contains("SLEEP")) {
                        BucketAggregators.incrementCount(grid, sleepBuckets, t);
                    }
                }
            }
        }
        if (sampleBuckets != null) {
            series.add(new AnalyticsModels.MetricSeries(
                    "wallSamplesPerSec",
                    "Wall samples",
                    "samples/s",
                    "Wall-clock sampling rate.",
                    BucketAggregators.countSeries(grid, sampleBuckets, 1.0)));
            List<AnalyticsModels.SeriesPoint> sleepPct = new ArrayList<>();
            for (TimeBucketGrid.BucketPoint bucket : grid.isoStarts()) {
                int i = bucket.index();
                double pct = sampleBuckets[i] == 0 ? 0.0 : 100.0 * sleepBuckets[i] / sampleBuckets[i];
                sleepPct.add(new AnalyticsModels.SeriesPoint(bucket.at(), pct, null, null, null));
            }
            series.add(new AnalyticsModels.MetricSeries(
                    "percentSleeping",
                    "Sleeping wall samples",
                    "percent",
                    "High value suggests threads blocked, waiting, or idle.",
                    sleepPct));
        }
        Map<String, Object> rollup = new LinkedHashMap<>();
        rollup.put("wallSampleCount", total);
        Map<String, Double> statePct = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : stateCounts.entrySet()) {
            statePct.put(e.getKey(), total == 0 ? 0.0 : 100.0 * e.getValue() / total);
        }
        rollup.put("stateBreakdownPercent", statePct);
        Map<String, Object> evidence = evidenceFromRanks(JfrAnalyticsSupport.topRanks(stacks, top(params)));
        List<Map<String, Object>> topThreads = new ArrayList<>();
        threadCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(top(params))
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("thread", e.getKey());
                    row.put("sampleCount", e.getValue());
                    topThreads.add(row);
                });
        evidence.put("topThreads", topThreads);
        return new AnalyticsModels.SubsystemResult(
                id(),
                availability,
                rollup,
                seriesOrNull(timeseries(params), series),
                evidence,
                List.of("Contrast with cpu subsystem to separate on-CPU vs off-CPU time."));
    }
}

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

final class GcSubsystemBuilder extends AnalyticsSubsystemSupport {
    private static final List<String> EXPECTED = List.of("jdk.GCPhasePause", "jdk.GarbageCollection");

    @Override
    public String id() {
        return "gc";
    }

    @Override
    public AnalyticsModels.SubsystemResult build(
            IItemCollection events,
            Set<String> available,
            TimeBucketGrid grid,
            AnalyticsModels.AnalyticsParams params,
            List<AnalyticsModels.Marker> markers) {
        var availability = avail(EXPECTED, available);
        List<Long> pauseNs = new ArrayList<>();
        for (String typeId : JfrAnalyticsSupport.present(JfrAnalyticsSupport.GC_PAUSE_TYPES, available)) {
            JfrAnalyticsSupport.collectPauseDurations(events, typeId, pauseNs);
        }
        if (pauseNs.isEmpty() && availability.status().equals(AnalyticsModels.AVAIL_ABSENT)) {
            return absent(id(), EXPECTED);
        }
        long youngGc = 0L;
        for (String typeId : JfrAnalyticsSupport.present(JfrAnalyticsSupport.YOUNG_GC_TYPES, available)) {
            youngGc += JfrAnalyticsSupport.countEvents(events, typeId);
        }
        double windowSec = grid == null ? 1.0 : grid.windowDurationSeconds();
        Map<String, Object> rollup = new LinkedHashMap<>(JfrAnalyticsSupport.pauseStats(pauseNs));
        rollup.put("youngGcCount", youngGc);
        rollup.put("youngGcPerHour", youngGc / windowSec * 3600.0);
        double pauseMs = ((Number) rollup.getOrDefault("sumMs", 0.0)).doubleValue();
        rollup.put("pauseTimePercentOfWindow", windowSec <= 0 ? 0.0 : 100.0 * pauseMs / 1000.0 / windowSec);
        rollup.put("phaseBreakdown", JfrAnalyticsSupport.gcPhaseBreakdown(events, available));
        List<AnalyticsModels.MetricSeries> series = new ArrayList<>();
        List<Map<String, Object>> longest = new ArrayList<>();
        if (timeseries(params) && grid != null) {
            int n = grid.bucketCount();
            int[] counts = BucketAggregators.newCountBuckets(n);
            double[] sumMs = BucketAggregators.newSumBuckets(n);
            double[] maxMs = BucketAggregators.newMaxBuckets(n);
            int[] youngBuckets = BucketAggregators.newCountBuckets(n);
            for (String typeId : JfrAnalyticsSupport.present(JfrAnalyticsSupport.GC_PAUSE_TYPES, available)) {
                for (IItemIterable iterable : events.apply(ItemFilters.type(typeId))) {
                    for (IItem item : iterable) {
                        Long dur = JfrItemAccess.durationNs(item);
                        if (dur == null || dur <= 0) {
                            continue;
                        }
                        long t = JfrItemAccess.startTimeNs(item);
                        double ms = dur / 1_000_000.0;
                        BucketAggregators.addDurationMs(grid, counts, sumMs, maxMs, t, ms);
                        if (ms >= 20.0) {
                            markers.add(new AnalyticsModels.Marker(
                                    instantIso(t), "gc_pause", String.format("%.2fms", ms), "gc"));
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("at", instantIso(t));
                        row.put("durationMs", ms);
                        row.put("type", typeId);
                        longest.add(row);
                    }
                }
            }
            for (String typeId : JfrAnalyticsSupport.present(JfrAnalyticsSupport.YOUNG_GC_TYPES, available)) {
                for (IItemIterable iterable : events.apply(ItemFilters.type(typeId))) {
                    for (IItem item : iterable) {
                        BucketAggregators.incrementCount(grid, youngBuckets, JfrItemAccess.startTimeNs(item));
                    }
                }
            }
            longest.sort((a, b) -> Double.compare(
                    ((Number) b.get("durationMs")).doubleValue(), ((Number) a.get("durationMs")).doubleValue()));
            if (longest.size() > 5) {
                longest = longest.subList(0, 5);
            }
            series.add(new AnalyticsModels.MetricSeries(
                    "pauseSumMsPerBucket",
                    "GC pause sum",
                    "ms",
                    "Sum of pause durations per bucket.",
                    BucketAggregators.durationSumSeries(grid, counts, sumMs, maxMs)));
            series.add(new AnalyticsModels.MetricSeries(
                    "youngGcCountPerBucket",
                    "Young GC events",
                    "count",
                    "Young collections per bucket.",
                    BucketAggregators.countSeries(grid, youngBuckets, 0)));
        }
        Map<String, Object> evidence = Map.of("longestPauses", longest);
        return new AnalyticsModels.SubsystemResult(
                id(),
                availability.status().equals(AnalyticsModels.AVAIL_ABSENT)
                        ? new AnalyticsModels.Availability(AnalyticsModels.AVAIL_PARTIAL, EXPECTED, availability.eventTypesSeen())
                        : availability,
                rollup,
                seriesOrNull(timeseries(params), series),
                evidence,
                List.of("Align pause timestamps with allocation and wall_time series."));
    }
}

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

final class HeapSubsystemBuilder extends AnalyticsSubsystemSupport {
    private static final List<String> EXPECTED = List.of(
            "jdk.GCHeapSummary", "jdk.MetaspaceSummary", "jdk.ResidentSetSize");

    @Override
    public String id() {
        return "heap";
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
        List<long[]> heapPoints = new ArrayList<>();
        List<long[]> metaPoints = new ArrayList<>();
        List<Double> rss = new ArrayList<>();
        List<Double> heapUsed = new ArrayList<>();
        List<AnalyticsModels.MetricSeries> series = new ArrayList<>();
        int n = grid == null ? 0 : grid.bucketCount();
        int[] heapCounts = n > 0 ? BucketAggregators.newGaugeCounts(n) : null;
        double[] heapSum = n > 0 ? BucketAggregators.newGaugeMeans(n) : null;
        double[] heapLast = n > 0 ? BucketAggregators.newGaugeLast(n) : null;
        int[] rssCounts = n > 0 ? BucketAggregators.newGaugeCounts(n) : null;
        double[] rssSum = n > 0 ? BucketAggregators.newGaugeMeans(n) : null;
        double[] rssLast = n > 0 ? BucketAggregators.newGaugeLast(n) : null;
        if (available.contains("jdk.GCHeapSummary")) {
            for (IItemIterable iterable : events.apply(ItemFilters.type("jdk.GCHeapSummary"))) {
                for (IItem item : iterable) {
                    long t = JfrItemAccess.startTimeNs(item);
                    long used = readQuantityBytes(item, "heapUsed");
                    if (used > 0) {
                        heapPoints.add(new long[] {t, used});
                        heapUsed.add((double) used);
                        if (heapCounts != null) {
                            BucketAggregators.addGauge(grid, heapCounts, heapSum, heapLast, t, used);
                        }
                    }
                }
            }
        }
        if (available.contains("jdk.MetaspaceSummary")) {
            for (IItemIterable iterable : events.apply(ItemFilters.type("jdk.MetaspaceSummary"))) {
                for (IItem item : iterable) {
                    long used = readQuantityBytes(item, "used");
                    if (used == 0) {
                        used = readQuantityBytes(item, "metaspaceUsed");
                    }
                    if (used > 0) {
                        metaPoints.add(new long[] {JfrItemAccess.startTimeNs(item), used});
                    }
                }
            }
        }
        if (available.contains("jdk.ResidentSetSize")) {
            for (IItemIterable iterable : events.apply(ItemFilters.type("jdk.ResidentSetSize"))) {
                for (IItem item : iterable) {
                    long t = JfrItemAccess.startTimeNs(item);
                    long size = readQuantityBytes(item, "size");
                    if (size > 0) {
                        rss.add((double) size);
                        if (rssCounts != null) {
                            BucketAggregators.addGauge(grid, rssCounts, rssSum, rssLast, t, size);
                        }
                    }
                }
            }
        }
        if (heapCounts != null && timeseries(params)) {
            series.add(new AnalyticsModels.MetricSeries(
                    "heapUsedBytes",
                    "Heap used",
                    "bytes",
                    "Rising post-GC plateau may indicate retention or leak.",
                    BucketAggregators.gaugeSeries(grid, heapCounts, heapSum, heapLast)));
            series.add(new AnalyticsModels.MetricSeries(
                    "rssBytes",
                    "RSS",
                    "bytes",
                    "RSS growth with flat heap may indicate native memory.",
                    BucketAggregators.gaugeSeries(grid, rssCounts, rssSum, rssLast)));
        }
        Map<String, Object> rollup = new LinkedHashMap<>();
        rollup.put("heapUsed", JfrAnalyticsSupport.numericSummary(heapUsed));
        rollup.put("rss", JfrAnalyticsSupport.numericSummary(rss));
        rollup.put("heapUsedSlopePerHour", JfrAnalyticsSupport.linearSlopePerHour(heapPoints));
        rollup.put("metaspaceUsedSlopePerHour", JfrAnalyticsSupport.linearSlopePerHour(metaPoints));
        double meanHeap = heapUsed.isEmpty() ? 0.0 : heapUsed.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double meanRss = rss.isEmpty() ? 0.0 : rss.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        rollup.put("heapVsRssGapMeanBytes", meanRss - meanHeap);
        return new AnalyticsModels.SubsystemResult(
                id(),
                availability,
                rollup,
                seriesOrNull(timeseries(params), series),
                Map.of(),
                List.of("Use with native_memory when RSS diverges from heap."));
    }
}

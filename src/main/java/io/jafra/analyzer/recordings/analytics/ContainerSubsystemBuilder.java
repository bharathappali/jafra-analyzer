package io.jafra.analyzer.recordings.analytics;

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

import io.jafra.analyzer.recordings.JfrAnalyticsSupport;
import io.jafra.analyzer.recordings.JfrItemAccess;
import io.jafra.analyzer.recordings.TimeBucketGrid;

final class ContainerSubsystemBuilder extends AnalyticsSubsystemSupport {
    private static final List<String> EXPECTED = List.of(
            "jdk.ContainerCPUThrottling", "jdk.ContainerMemoryUsage", "jdk.CPULoad");

    @Override
    public String id() {
        return "container";
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
        List<AnalyticsModels.MetricSeries> series = new ArrayList<>();
        Map<String, Object> rollup = new LinkedHashMap<>();
        long throttleStart = -1L;
        long throttleEnd = -1L;
        if (available.contains("jdk.ContainerCPUThrottling")) {
            for (IItemIterable iterable : events.apply(ItemFilters.type("jdk.ContainerCPUThrottling"))) {
                for (IItem item : iterable) {
                    long t = readQuantityBytes(item, "cpuThrottledTime");
                    if (throttleStart < 0) {
                        throttleStart = t;
                    }
                    throttleEnd = t;
                    long delta = throttleEnd - throttleStart;
                    if (delta > 0 && markers != null) {
                        markers.add(new AnalyticsModels.Marker(
                                instantIso(JfrItemAccess.startTimeNs(item)),
                                "cpu_throttle",
                                String.valueOf(delta),
                                "container"));
                    }
                }
            }
            if (throttleStart >= 0 && throttleEnd >= throttleStart) {
                rollup.put("cpuThrottledTimeDeltaNs", throttleEnd - throttleStart);
            }
        }
        List<Double> mem = new ArrayList<>();
        if (available.contains("jdk.ContainerMemoryUsage")) {
            for (IItemIterable iterable : events.apply(ItemFilters.type("jdk.ContainerMemoryUsage"))) {
                for (IItem item : iterable) {
                    long usage = readQuantityBytes(item, "memoryUsage");
                    if (usage > 0) {
                        mem.add((double) usage);
                    }
                }
            }
        }
        rollup.put("memoryUsage", JfrAnalyticsSupport.numericSummary(mem));
        if (timeseries(params) && grid != null && available.contains("jdk.ContainerCPUThrottling")) {
            series.add(new AnalyticsModels.MetricSeries(
                    "throttleRatio",
                    "CPU throttle ratio",
                    "ratio",
                    "Per-bucket delta of cpuThrottledSlices over cpuElapsedSlices.",
                    throttleRatioSeries(events, grid)));
        }
        if (timeseries(params) && grid != null && available.contains("jdk.ContainerMemoryUsage")) {
            int n = grid.bucketCount();
            int[] counts = BucketAggregators.newGaugeCounts(n);
            double[] sums = BucketAggregators.newGaugeMeans(n);
            double[] last = BucketAggregators.newGaugeLast(n);
            for (IItemIterable iterable : events.apply(ItemFilters.type("jdk.ContainerMemoryUsage"))) {
                for (IItem item : iterable) {
                    long usage = readQuantityBytes(item, "memoryUsage");
                    BucketAggregators.addGauge(grid, counts, sums, last, JfrItemAccess.startTimeNs(item), usage);
                }
            }
            series.add(new AnalyticsModels.MetricSeries(
                    "memoryUsageBytes",
                    "Container memory",
                    "bytes",
                    " cgroup memory usage.",
                    BucketAggregators.gaugeSeries(grid, counts, sums, last)));
        }
        return new AnalyticsModels.SubsystemResult(
                id(),
                availability,
                rollup,
                seriesOrNull(timeseries(params), series),
                Map.of(),
                List.of("Throttling with low JVM CPU suggests cgroup quota limits."));
    }

    private static List<AnalyticsModels.SeriesPoint> throttleRatioSeries(IItemCollection events, TimeBucketGrid grid) {
        List<long[]> samples = new ArrayList<>();
        for (IItemIterable iterable : events.apply(ItemFilters.type("jdk.ContainerCPUThrottling"))) {
            for (IItem item : iterable) {
                Long throttled = JfrItemAccess.quantityLong(item, "cpuThrottledSlices");
                Long elapsed = JfrItemAccess.quantityLong(item, "cpuElapsedSlices");
                if (throttled == null || elapsed == null) {
                    continue;
                }
                samples.add(new long[] {JfrItemAccess.startTimeNs(item), throttled, elapsed});
            }
        }
        samples.sort(Comparator.comparingLong(sample -> sample[0]));
        int n = grid.bucketCount();
        double[] throttledDelta = new double[n];
        double[] elapsedDelta = new double[n];
        for (int i = 1; i < samples.size(); i++) {
            long[] previous = samples.get(i - 1);
            long[] current = samples.get(i);
            long dThrottled = current[1] - previous[1];
            long dElapsed = current[2] - previous[2];
            if (dElapsed <= 0 || dThrottled < 0) {
                continue;
            }
            int idx = grid.indexFor(current[0]);
            if (idx < 0) {
                continue;
            }
            throttledDelta[idx] += dThrottled;
            elapsedDelta[idx] += dElapsed;
        }
        List<AnalyticsModels.SeriesPoint> points = new ArrayList<>(n);
        for (TimeBucketGrid.BucketPoint bucket : grid.isoStarts()) {
            int i = bucket.index();
            Number ratio = elapsedDelta[i] > 0 ? throttledDelta[i] / elapsedDelta[i] : null;
            points.add(new AnalyticsModels.SeriesPoint(bucket.at(), ratio, null, null, null));
        }
        return points;
    }
}

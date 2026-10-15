package io.jafra.analyzer.recordings.analytics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.ItemFilters;

import io.jafra.analyzer.recordings.JfrAnalyticsSupport;
import io.jafra.analyzer.recordings.JfrItemAccess;
import io.jafra.analyzer.recordings.TimeBucketGrid;

final class AllocationsSubsystemBuilder extends AnalyticsSubsystemSupport {
    private static final List<String> EXPECTED = List.of(
            "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB", "jdk.ObjectAllocationSample");

    @Override
    public String id() {
        return "allocations";
    }

    @Override
    public AnalyticsModels.SubsystemResult build(
            IItemCollection events,
            Set<String> available,
            TimeBucketGrid grid,
            AnalyticsModels.AnalyticsParams params,
            List<AnalyticsModels.Marker> markers) {
        var availability = avail(EXPECTED, available);
        List<String> types = JfrAnalyticsSupport.present(JfrAnalyticsSupport.ALLOCATION_TYPES, available);
        if (types.isEmpty()) {
            return absent(id(), EXPECTED);
        }
        Map<String, JfrAnalyticsSupport.MutableRank> byClass = new LinkedHashMap<>();
        Map<String, JfrAnalyticsSupport.MutableRank> byStack = new LinkedHashMap<>();
        JfrAnalyticsSupport.rankAllocations(events, types, byClass, byStack);
        long eventCount = 0L;
        long bytes = 0L;
        long outside = 0L;
        for (JfrAnalyticsSupport.MutableRank rank : byClass.values()) {
            eventCount += rank.count;
            bytes += rank.bytes;
        }
        if (available.contains("jdk.ObjectAllocationOutsideTLAB")
                || available.contains("profiler.ObjectAllocationOutsideTLAB")) {
            outside = JfrAnalyticsSupport.countEvents(events, "jdk.ObjectAllocationOutsideTLAB")
                    + JfrAnalyticsSupport.countEvents(events, "profiler.ObjectAllocationOutsideTLAB");
        }
        double windowSec = grid == null ? 1.0 : grid.windowDurationSeconds();
        Map<String, Object> rollup = new LinkedHashMap<>();
        rollup.put("allocationEventCount", eventCount);
        rollup.put("allocationBytesTotal", bytes);
        rollup.put("allocationBytesPerSec", bytes / windowSec);
        rollup.put("allocationEventsPerSec", eventCount / windowSec);
        rollup.put("outsideTlabEventCount", outside);
        rollup.put("outsideTlabRatio", eventCount == 0 ? 0.0 : (double) outside / eventCount);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put(
                "topClasses",
                evidenceFromRanks(JfrAnalyticsSupport.topRanks(byClass, top(params)), params.stackIds()).get("top"));
        evidence.put(
                "topStacks",
                evidenceFromRanks(JfrAnalyticsSupport.topRanks(byStack, top(params)), params.stackIds()).get("top"));
        List<AnalyticsModels.MetricSeries> series = new ArrayList<>();
        if (timeseries(params) && grid != null) {
            int n = grid.bucketCount();
            int[] counts = BucketAggregators.newCountBuckets(n);
            double[] sums = BucketAggregators.newSumBuckets(n);
            double[] max = BucketAggregators.newMaxBuckets(n);
            for (String typeId : types) {
                for (var iterable : events.apply(ItemFilters.type(typeId))) {
                    for (var item : iterable) {
                        BucketAggregators.addPointSum(
                                grid,
                                counts,
                                sums,
                                JfrItemAccess.startTimeNs(item),
                                JfrItemAccess.allocationBytes(item));
                    }
                }
            }
            series.add(new AnalyticsModels.MetricSeries(
                    "allocationBytesPerBucket",
                    "Allocation bytes",
                    "bytes",
                    "Bytes allocated in each bucket. Empty buckets are null.",
                    BucketAggregators.durationSumSeries(grid, counts, sums, max)));
        }
        return new AnalyticsModels.SubsystemResult(
                id(),
                availability,
                rollup,
                seriesOrNull(timeseries(params), series),
                evidence,
                List.of("Correlate allocation spikes with gc pauses."));
    }
}

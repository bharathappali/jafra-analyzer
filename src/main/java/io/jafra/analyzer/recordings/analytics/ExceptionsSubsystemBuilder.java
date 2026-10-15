package io.jafra.analyzer.recordings.analytics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openjdk.jmc.common.item.IItemCollection;

import io.jafra.analyzer.recordings.JfrAnalyticsSupport;
import io.jafra.analyzer.recordings.JfrItemAccess;
import io.jafra.analyzer.recordings.StackIds;
import io.jafra.analyzer.recordings.TimeBucketGrid;

final class ExceptionsSubsystemBuilder extends AnalyticsSubsystemSupport {
    private static final List<String> EXPECTED = List.of("jdk.JavaExceptionThrow", "jdk.ExceptionStatistics");

    @Override
    public String id() {
        return "exceptions";
    }

    @Override
    public AnalyticsModels.SubsystemResult build(
            IItemCollection events,
            Set<String> available,
            TimeBucketGrid grid,
            AnalyticsModels.AnalyticsParams params,
            List<AnalyticsModels.Marker> markers) {
        var availability = avail(EXPECTED, available);
        List<String> types = JfrAnalyticsSupport.present(JfrAnalyticsSupport.EXCEPTION_TYPES, available);
        if (types.isEmpty() && !available.contains("jdk.ExceptionStatistics")) {
            return absent(id(), EXPECTED);
        }
        Map<String, JfrAnalyticsSupport.MutableRank> byClass = new LinkedHashMap<>();
        Map<String, JfrAnalyticsSupport.MutableRank> byStack = new LinkedHashMap<>();
        int n = grid == null ? 0 : grid.bucketCount();
        int[] throwBuckets = timeseries(params) && grid != null ? BucketAggregators.newCountBuckets(n) : null;
        for (String typeId : types) {
            for (var iterable : events.apply(org.openjdk.jmc.common.item.ItemFilters.type(typeId))) {
                for (var item : iterable) {
                    String clazz = JfrItemAccess.text(item, "thrownClass", "exceptionClass", "class");
                    if (clazz == null) {
                        clazz = "(unknown)";
                    }
                    JfrAnalyticsSupport.bump(byClass, "class", clazz, null, 0L, 0L);
                    StackIds.Identity identity = JfrItemAccess.stackIdentity(item);
                    String stack = JfrItemAccess.stackTrace(item);
                    if (identity != null && stack != null) {
                        JfrAnalyticsSupport.bump(byStack, "stack", stack, stack, identity, 0L, 0L);
                    }
                    if (throwBuckets != null) {
                        BucketAggregators.incrementCount(grid, throwBuckets, JfrItemAccess.startTimeNs(item));
                    }
                }
            }
        }
        Map<String, Object> rollup = new LinkedHashMap<>();
        long throwCount = 0L;
        for (JfrAnalyticsSupport.MutableRank rank : byClass.values()) {
            throwCount += rank.count;
        }
        rollup.put("throwCount", throwCount);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put(
                "topClasses",
                evidenceFromRanks(JfrAnalyticsSupport.topRanks(byClass, top(params)), params.stackIds()).get("top"));
        evidence.put(
                "topStacks",
                evidenceFromRanks(JfrAnalyticsSupport.topRanks(byStack, top(params)), params.stackIds()).get("top"));
        List<AnalyticsModels.MetricSeries> series = new ArrayList<>();
        if (throwBuckets != null) {
            series.add(new AnalyticsModels.MetricSeries(
                    "throwsPerBucket",
                    "Exception throws",
                    "count",
                    "JavaExceptionThrow and JavaErrorThrow events per bucket. Empty buckets are null.",
                    BucketAggregators.nullableCountSeries(grid, throwBuckets)));
        }
        return new AnalyticsModels.SubsystemResult(
                id(),
                availability,
                rollup,
                seriesOrNull(timeseries(params), series),
                evidence,
                List.of("Hot exception paths add overhead even when handled."));
    }
}

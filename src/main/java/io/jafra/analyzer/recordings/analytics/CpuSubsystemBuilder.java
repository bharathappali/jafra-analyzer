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

import io.jafra.analyzer.recordings.JfrAggregationService;
import io.jafra.analyzer.recordings.JfrAnalyticsSupport;
import io.jafra.analyzer.recordings.JfrItemAccess;
import io.jafra.analyzer.recordings.TimeBucketGrid;

final class CpuSubsystemBuilder extends AnalyticsSubsystemSupport {
    private static final List<String> EXPECTED = List.of(
            "jdk.ExecutionSample", "jdk.CPULoad", "jdk.ThreadCPULoad", "profiler.ExecutionSample");

    @Override
    public String id() {
        return "cpu";
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
        List<String> sampleTypes = JfrAnalyticsSupport.present(JfrAnalyticsSupport.CPU_SAMPLE_TYPES, available);
        Map<String, JfrAnalyticsSupport.MutableRank> stacks = new LinkedHashMap<>();
        JfrAnalyticsSupport.rankStacks(events, sampleTypes, stacks, 1L);
        long sampleCount = 0L;
        for (String typeId : sampleTypes) {
            sampleCount += JfrAnalyticsSupport.countEvents(events, typeId);
        }
        List<Double> jvmCpu = new ArrayList<>();
        List<Double> machine = new ArrayList<>();
        List<AnalyticsModels.MetricSeries> series = new ArrayList<>();
        if (timeseries(params) && grid != null && available.contains("jdk.CPULoad")) {
            int n = grid.bucketCount();
            int[] counts = BucketAggregators.newCountBuckets(n);
            double[] jvmSum = BucketAggregators.newGaugeMeans(n);
            double[] machSum = BucketAggregators.newGaugeMeans(n);
            int[] jvmCounts = BucketAggregators.newGaugeCounts(n);
            int[] machCounts = BucketAggregators.newGaugeCounts(n);
            double[] jvmLast = BucketAggregators.newGaugeLast(n);
            double[] machLast = BucketAggregators.newGaugeLast(n);
            for (IItemIterable iterable : events.apply(ItemFilters.type("jdk.CPULoad"))) {
                for (IItem item : iterable) {
                    long t = JfrItemAccess.startTimeNs(item);
                    Double user = readDouble(item, "jvmUser");
                    Double system = readDouble(item, "jvmSystem");
                    Double total = readDouble(item, "machineTotal");
                    if (user != null && system != null) {
                        double jvm = user + system;
                        jvmCpu.add(jvm);
                        BucketAggregators.addGauge(grid, jvmCounts, jvmSum, jvmLast, t, jvm);
                    }
                    if (total != null) {
                        machine.add(total);
                        BucketAggregators.addGauge(grid, machCounts, machSum, machLast, t, total);
                    }
                }
            }
            series.add(new AnalyticsModels.MetricSeries(
                    "jvmCpuPercent",
                    "JVM CPU",
                    "percent",
                    "High value suggests the JVM process is using CPU time.",
                    BucketAggregators.gaugeSeries(grid, jvmCounts, jvmSum, jvmLast)));
            series.add(new AnalyticsModels.MetricSeries(
                    "machineTotalPercent",
                    "Machine CPU",
                    "percent",
                    "High value with low JVM CPU suggests other processes or system load.",
                    BucketAggregators.gaugeSeries(grid, machCounts, machSum, machLast)));
            int[] sampleBuckets = BucketAggregators.newCountBuckets(n);
            for (String typeId : sampleTypes) {
                for (IItemIterable iterable : events.apply(ItemFilters.type(typeId))) {
                    for (IItem item : iterable) {
                        BucketAggregators.incrementCount(grid, sampleBuckets, JfrItemAccess.startTimeNs(item));
                    }
                }
            }
            series.add(new AnalyticsModels.MetricSeries(
                    "cpuSampleRatePerSec",
                    "CPU samples",
                    "samples/s",
                    "Sampling rate of on-CPU stack events.",
                    BucketAggregators.countSeries(grid, sampleBuckets, 1.0)));
        } else if (available.contains("jdk.CPULoad")) {
            for (IItemIterable iterable : events.apply(ItemFilters.type("jdk.CPULoad"))) {
                for (IItem item : iterable) {
                    Double user = readDouble(item, "jvmUser");
                    Double system = readDouble(item, "jvmSystem");
                    Double total = readDouble(item, "machineTotal");
                    if (user != null && system != null) {
                        jvmCpu.add(user + system);
                    }
                    if (total != null) {
                        machine.add(total);
                    }
                }
            }
        }
        Map<String, Object> rollup = new LinkedHashMap<>();
        rollup.put("cpuSampleCount", sampleCount);
        rollup.put("jvmCpu", JfrAnalyticsSupport.numericSummary(jvmCpu));
        rollup.put("machineTotal", JfrAnalyticsSupport.numericSummary(machine));
        List<JfrAggregationService.RankedEntry> topStacks = JfrAnalyticsSupport.topRanks(stacks, top(params));
        if (!topStacks.isEmpty() && sampleCount > 0) {
            List<Map<String, Object>> withPct = new ArrayList<>();
            for (JfrAggregationService.RankedEntry entry : topStacks) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("stack", entry.stack());
                row.put("sampleCount", entry.count());
                row.put("percentOfCpuSamples", 100.0 * entry.count() / sampleCount);
                withPct.add(row);
            }
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("topStacks", withPct);
            return new AnalyticsModels.SubsystemResult(
                    id(),
                    availability,
                    rollup,
                    seriesOrNull(timeseries(params), series),
                    evidence,
                    List.of("Compare with wall_time when latency exceeds CPU hotspots."));
        }
        return new AnalyticsModels.SubsystemResult(
                id(),
                availability,
                rollup,
                seriesOrNull(timeseries(params), series),
                evidenceFromRanks(topStacks),
                List.of("Compare with wall_time when latency exceeds CPU hotspots."));
    }
}

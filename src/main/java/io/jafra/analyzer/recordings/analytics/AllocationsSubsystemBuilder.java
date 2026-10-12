package io.jafra.analyzer.recordings.analytics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openjdk.jmc.common.item.IItemCollection;

import io.jafra.analyzer.recordings.JfrAnalyticsSupport;
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
        evidence.put("topClasses", evidenceFromRanks(JfrAnalyticsSupport.topRanks(byClass, top(params))).get("top"));
        evidence.put("topStacks", evidenceFromRanks(JfrAnalyticsSupport.topRanks(byStack, top(params))).get("top"));
        return new AnalyticsModels.SubsystemResult(
                id(),
                availability,
                rollup,
                null,
                evidence,
                List.of("Correlate allocation spikes with gc pauses."));
    }
}

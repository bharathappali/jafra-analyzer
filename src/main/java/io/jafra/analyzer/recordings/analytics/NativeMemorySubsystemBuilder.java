package io.jafra.analyzer.recordings.analytics;

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
import io.jafra.analyzer.recordings.StackIds;
import io.jafra.analyzer.recordings.TimeBucketGrid;

final class NativeMemorySubsystemBuilder extends AnalyticsSubsystemSupport {
    private static final List<String> EXPECTED = List.of("profiler.Malloc", "profiler.Free", "profiler.LiveObject");

    @Override
    public String id() {
        return "native_memory";
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
        long mallocCount = JfrAnalyticsSupport.countEvents(events, "profiler.Malloc");
        long freeCount = JfrAnalyticsSupport.countEvents(events, "profiler.Free");
        long mallocBytes = 0L;
        Map<String, JfrAnalyticsSupport.MutableRank> stacks = new LinkedHashMap<>();
        if (available.contains("profiler.Malloc")) {
            for (IItemIterable iterable : events.apply(ItemFilters.type("profiler.Malloc"))) {
                for (IItem item : iterable) {
                    long size = readQuantityBytes(item, "size");
                    mallocBytes += size;
                    StackIds.Identity identity = JfrItemAccess.stackIdentity(item);
                    String stack = JfrItemAccess.stackTrace(item);
                    if (identity != null && stack != null) {
                        JfrAnalyticsSupport.bump(stacks, "stack", stack, stack, identity, size, 0L);
                    }
                }
            }
        }
        double windowSec = grid == null ? 1.0 : grid.windowDurationSeconds();
        Map<String, Object> rollup = new LinkedHashMap<>();
        rollup.put("mallocCount", mallocCount);
        rollup.put("freeCount", freeCount);
        rollup.put("mallocBytesTotal", mallocBytes);
        rollup.put("mallocBytesPerSec", mallocBytes / windowSec);
        rollup.put("freeEventsPerSec", freeCount / windowSec);
        return new AnalyticsModels.SubsystemResult(
                id(),
                availability,
                rollup,
                null,
                evidenceFromRanks(JfrAnalyticsSupport.topRanks(stacks, top(params)), params.stackIds()),
                List.of("High free churn may include profiler hook overhead."));
    }
}

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
import io.jafra.analyzer.recordings.TimeBucketGrid;

final class JitSubsystemBuilder extends AnalyticsSubsystemSupport {
    private static final List<String> EXPECTED = List.of(
            "jdk.Compilation", "jdk.Deoptimization", "jdk.CodeCacheStatistics");

    @Override
    public String id() {
        return "jit";
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
        long compilations = JfrAnalyticsSupport.countEvents(events, "jdk.Compilation");
        long deopts = JfrAnalyticsSupport.countEvents(events, "jdk.Deoptimization");
        long compilerMallocBytes = 0L;
        if (available.contains("profiler.Malloc")) {
            for (IItemIterable iterable : events.apply(ItemFilters.type("profiler.Malloc"))) {
                for (IItem item : iterable) {
                    String stack = JfrItemAccess.stackTrace(item);
                    if (stack != null && (stack.contains("C2") || stack.contains("Compiler"))) {
                        compilerMallocBytes += readQuantityBytes(item, "size");
                    }
                }
            }
        }
        Map<String, Object> rollup = new LinkedHashMap<>();
        rollup.put("compilationCount", compilations);
        rollup.put("deoptimizationCount", deopts);
        rollup.put("compilerNativeMallocBytes", compilerMallocBytes);
        return new AnalyticsModels.SubsystemResult(
                id(),
                availability,
                rollup,
                null,
                Map.of(),
                List.of("High compiler malloc with compilations suggests C2 arena churn."));
    }
}

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

final class VmInternalSubsystemBuilder extends AnalyticsSubsystemSupport {
    private static final List<String> EXPECTED = List.of("jdk.ExecuteVMOperation", "jdk.SafepointBegin", "jdk.DataLoss");

    @Override
    public String id() {
        return "vm_internal";
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
        long vmOps = 0L;
        double vmMs = 0.0;
        if (available.contains("jdk.ExecuteVMOperation")) {
            for (IItemIterable iterable : events.apply(ItemFilters.type("jdk.ExecuteVMOperation"))) {
                for (IItem item : iterable) {
                    Long dur = JfrItemAccess.durationNs(item);
                    if (dur != null && dur >= 10_000_000L) {
                        vmOps++;
                        vmMs += dur / 1_000_000.0;
                    }
                }
            }
        }
        long safepoints = JfrAnalyticsSupport.countEvents(events, "jdk.SafepointBegin");
        long dataLoss = JfrAnalyticsSupport.countEvents(events, "jdk.DataLoss");
        Map<String, Object> rollup = new LinkedHashMap<>();
        rollup.put("vmOperationCountGte10ms", vmOps);
        rollup.put("vmOperationSumMsGte10ms", vmMs);
        rollup.put("safepointBeginCount", safepoints);
        rollup.put("dataLossCount", dataLoss);
        if (dataLoss > 0 && markers != null) {
            markers.add(new AnalyticsModels.Marker(null, "data_loss", String.valueOf(dataLoss), "vm_internal"));
        }
        return new AnalyticsModels.SubsystemResult(
                id(),
                availability,
                rollup,
                null,
                Map.of(),
                List.of("DataLoss indicates JFR buffer overrun."));
    }
}

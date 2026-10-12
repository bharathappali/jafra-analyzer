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

final class IoSubsystemBuilder extends AnalyticsSubsystemSupport {
    private static final List<String> EXPECTED = List.of(
            "jdk.FileRead", "jdk.SocketRead", "jdk.ContainerIOUsage");

    @Override
    public String id() {
        return "io";
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
        long readCount = 0L;
        double readMs = 0.0;
        for (String type : List.of("jdk.FileRead", "jdk.SocketRead", "jdk.FileWrite", "jdk.SocketWrite")) {
            if (!available.contains(type)) {
                continue;
            }
            for (IItemIterable iterable : events.apply(ItemFilters.type(type))) {
                for (IItem item : iterable) {
                    readCount++;
                    Long dur = JfrItemAccess.durationNs(item);
                    if (dur != null) {
                        readMs += dur / 1_000_000.0;
                    }
                }
            }
        }
        Map<String, Object> rollup = new LinkedHashMap<>();
        rollup.put("ioOperationCount", readCount);
        rollup.put("ioDurationSumMs", readMs);
        if (available.contains("jdk.ContainerIOUsage")) {
            long firstBytes = -1L;
            long lastBytes = -1L;
            for (IItemIterable iterable : events.apply(ItemFilters.type("jdk.ContainerIOUsage"))) {
                for (IItem item : iterable) {
                    long bytes = readQuantityBytes(item, "dataTransferred");
                    if (firstBytes < 0) {
                        firstBytes = bytes;
                    }
                    lastBytes = bytes;
                }
            }
            if (firstBytes >= 0 && lastBytes >= firstBytes) {
                rollup.put("containerIoBytesDelta", lastBytes - firstBytes);
            }
        }
        return new AnalyticsModels.SubsystemResult(
                id(),
                availability,
                rollup,
                null,
                Map.of(),
                List.of("Sparse IO events are common; correlate slow reads with wall_time."));
    }
}

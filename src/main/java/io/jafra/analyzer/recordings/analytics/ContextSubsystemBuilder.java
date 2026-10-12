package io.jafra.analyzer.recordings.analytics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.ItemFilters;

import io.jafra.analyzer.recordings.JfrItemAccess;
import io.jafra.analyzer.recordings.TimeBucketGrid;

final class ContextSubsystemBuilder extends AnalyticsSubsystemSupport {
    private static final List<String> EXPECTED = List.of(
            "jdk.JVMInformation",
            "jdk.GCConfiguration",
            "jdk.ContainerConfiguration",
            "jdk.ActiveRecording");

    @Override
    public String id() {
        return "context";
    }

    @Override
    public AnalyticsModels.SubsystemResult build(
            IItemCollection events,
            Set<String> available,
            TimeBucketGrid grid,
            AnalyticsModels.AnalyticsParams params,
            List<AnalyticsModels.Marker> markers) {
        Map<String, Object> rollup = new LinkedHashMap<>();
        putFirstEvent(events, available, "jdk.JVMInformation", rollup, "jvm");
        putFirstEvent(events, available, "jdk.GCConfiguration", rollup, "gcConfiguration");
        putFirstEvent(events, available, "jdk.GCHeapConfiguration", rollup, "heapConfiguration");
        putFirstEvent(events, available, "jdk.ContainerConfiguration", rollup, "containerConfiguration");
        putFirstEvent(events, available, "jdk.OSInformation", rollup, "os");
        long profilerTypes = available.stream().filter(t -> t.startsWith("profiler.")).count();
        long jdkTypes = available.stream().filter(t -> t.startsWith("jdk.")).count();
        rollup.put("eventTypesProfiler", profilerTypes);
        rollup.put("eventTypesJdk", jdkTypes);
        var availability = avail(EXPECTED, available);
        if (availability.status().equals(AnalyticsModels.AVAIL_ABSENT)) {
            return absent(id(), EXPECTED);
        }
        return new AnalyticsModels.SubsystemResult(
                id(),
                availability,
                rollup,
                null,
                Map.of(),
                List.of("Baseline JVM and platform context for interpreting other subsystems."));
    }

    private static void putFirstEvent(
            IItemCollection events,
            Set<String> available,
            String typeId,
            Map<String, Object> rollup,
            String key) {
        if (!available.contains(typeId)) {
            return;
        }
        for (IItemIterable iterable : events.apply(ItemFilters.type(typeId))) {
            for (IItem item : iterable) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("jdkVersion", JfrItemAccess.text(item, "jdkVersion", "vmVersion"));
                row.put("vmName", JfrItemAccess.text(item, "jvmName", "vmName"));
                row.put("gcName", JfrItemAccess.text(item, "gc", "name"));
                row.put("memoryLimit", JfrItemAccess.text(item, "memoryLimit"));
                row.put("containerType", JfrItemAccess.text(item, "containerType"));
                row.put("effectiveCpuCount", JfrItemAccess.text(item, "effectiveCpuCount"));
                rollup.put(key, row);
                return;
            }
        }
    }
}

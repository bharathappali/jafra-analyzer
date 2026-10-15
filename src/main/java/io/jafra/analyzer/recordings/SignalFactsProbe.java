package io.jafra.analyzer.recordings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.ItemFilters;

import io.jafra.analyzer.signals.ContainerMath;
import io.jafra.analyzer.signals.SignalRegistry;

/** Reads the JVM, GC, flag, and container events v1 already loaded. */
public final class SignalFactsProbe {
    private SignalFactsProbe() {}

    public static SignalRegistry.Facts from(IItemCollection events, Set<String> available) {
        try {
            return scan(events, available);
        } catch (RuntimeException ignored) {
            return SignalRegistry.Facts.empty();
        }
    }

    private static SignalRegistry.Facts scan(IItemCollection events, Set<String> available) {
        Integer feature = null;
        String young = null;
        String old = null;
        Map<String, String> flags = new LinkedHashMap<>();
        boolean sawFlags = false;
        boolean inContainer = false;
        Long cpus = null;
        Long mem = null;
        Map<String, Double> thresholds = new LinkedHashMap<>();
        boolean profiler = available.stream().anyMatch(id -> id.startsWith("profiler."));
        if (available.contains("jdk.JVMInformation")) {
            for (IItem item : items(events, "jdk.JVMInformation")) {
                feature = ContainerMath.jdkFeature(JfrItemAccess.text(item, "jvmVersion", "javaVersion"));
                if (feature != null) {
                    break;
                }
            }
        }
        if (available.contains("jdk.GCConfiguration")) {
            for (IItem item : items(events, "jdk.GCConfiguration")) {
                young = JfrItemAccess.text(item, "youngCollector", "young");
                old = JfrItemAccess.text(item, "oldCollector", "old", "gcName");
                break;
            }
        }
        for (String type : new String[] {"jdk.BooleanFlag", "jdk.StringFlag"}) {
            if (!available.contains(type)) {
                continue;
            }
            sawFlags = true;
            for (IItem item : items(events, type)) {
                String name = JfrItemAccess.text(item, "name");
                String value = JfrItemAccess.text(item, "value");
                if (name != null) {
                    flags.put(name, value);
                }
            }
        }
        if (available.contains("jdk.ContainerConfiguration")) {
            inContainer = true;
            for (IItem item : items(events, "jdk.ContainerConfiguration")) {
                Long rawCpus = JfrItemAccess.quantityLong(item, "effectiveCpuCount");
                Long rawMem = JfrItemAccess.quantityLong(item, "memoryLimit");
                cpus = rawCpus;
                mem = ContainerMath.memLimit(rawMem);
                break;
            }
        }
        if (available.contains("jdk.ActiveSetting")) {
            for (IItem item : items(events, "jdk.ActiveSetting")) {
                String setting = JfrItemAccess.text(item, "setting", "name");
                if (!"threshold".equals(setting)) {
                    continue;
                }
                String eventType = JfrItemAccess.text(item, "id", "name");
                Double ms = ContainerMath.millis(JfrItemAccess.text(item, "value"));
                if (eventType != null && ms != null) {
                    thresholds.put(eventType, ms);
                }
            }
        }
        String gc = ContainerMath.gcFamily(young, old, flags);
        String selected = ContainerMath.gcSelectedBy(sawFlags, flags.entrySet().stream()
                .map(entry -> new ContainerMath.Flag(entry.getKey(), entry.getValue(), flagOrigin(events, available, entry.getKey())))
                .toList());
        return new SignalRegistry.Facts(feature, gc, selected, flags, sawFlags, inContainer, available, profiler, thresholds, cpus, mem, null);
    }

    private static String flagOrigin(IItemCollection events, Set<String> available, String name) {
        for (String type : new String[] {"jdk.BooleanFlag", "jdk.StringFlag"}) {
            if (!available.contains(type)) {
                continue;
            }
            for (IItem item : items(events, type)) {
                if (name.equals(JfrItemAccess.text(item, "name"))) {
                    return JfrItemAccess.text(item, "origin");
                }
            }
        }
        return null;
    }

    private static Iterable<IItem> items(IItemCollection events, String type) {
        java.util.List<IItem> items = new java.util.ArrayList<>();
        for (IItemIterable iterable : events.apply(ItemFilters.type(type))) {
            for (IItem item : iterable) {
                items.add(item);
            }
        }
        return items;
    }
}

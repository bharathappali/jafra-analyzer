package io.jafra.analyzer.v2.incidents;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.catalogue.Catalogue;
import io.jafra.analyzer.v2.catalogue.MetricSpec;
import io.jafra.analyzer.v2.rollup.LogHistogram;
import io.jafra.analyzer.v2.store.V2Store;
import io.jafra.analyzer.v2.store.V2Store.BaselineSlot;

/**
 * Median, raw MAD, and merged p99 over the last 120 processed windows before the current one.
 * Windows of an incident in the same group are skipped and do not consume a slot. A window with no
 * row for the metric still consumes a slot. The current window is never included.
 */
@ApplicationScoped
public class BaselineService {
    static final int WINDOW_CAP = 120;
    static final int PROVEN = 60;

    private final V2Store store;
    private final Catalogue catalogue;

    @Inject
    public BaselineService(V2Store store, Catalogue catalogue) {
        this.store = store;
        this.catalogue = catalogue;
    }

    public Baselines bind(int jvmId, long beforeMs) {
        Map<String, Baseline> cache = new HashMap<>();
        return (name, key, group, stat) -> {
            String cacheKey = name + "\0" + key + "\0" + group + "\0" + stat;
            Baseline cached = cache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            try {
                Baseline computed = baseline(jvmId, name, key, group, stat, beforeMs);
                cache.put(cacheKey, computed);
                return computed;
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        };
    }

    public Baseline baseline(int jvmId, String name, String key, String group, String stat, long beforeMs)
            throws IOException {
        String usedKey = key == null ? "" : key;
        String usedStat = stat == null || stat.isBlank() ? "avg" : stat;
        String usedGroup = group == null ? "" : group;
        List<BaselineSlot> slots = store.baselineSlots(jvmId, name, usedKey, usedGroup, beforeMs, WINDOW_CAP);
        boolean histogram = histogram(name);
        List<Double> values = new ArrayList<>();
        long events = 0;
        double seriesMin = Double.POSITIVE_INFINITY;
        double seriesMax = Double.NEGATIVE_INFINITY;
        for (BaselineSlot slot : slots) {
            if (!slot.present()) {
                continue;
            }
            events += slot.count();
            seriesMin = Math.min(seriesMin, slot.min());
            seriesMax = Math.max(seriesMax, slot.max());
            if (!"p99".equals(usedStat)) {
                values.add(stat(slot, usedStat));
            }
        }
        Double p99 = null;
        if (histogram && !valuedAt(slots).isEmpty()) {
            Map<Long, Map<Integer, Long>> buckets = store.readHistAt(jvmId, name, usedKey, valuedAt(slots));
            p99 = finite(LogHistogram.percentile(LogHistogram.merge(buckets.values()), 0.99, finiteMin(seriesMin), finiteMax(seriesMax, seriesMin)));
            if ("p99".equals(usedStat)) {
                for (BaselineSlot slot : slots) {
                    if (!slot.present()) {
                        continue;
                    }
                    Map<Integer, Long> window = buckets.get(slot.ts());
                    if (window == null || window.isEmpty()) {
                        continue;
                    }
                    Double one = finite(LogHistogram.percentile(window, 0.99, slot.min(), slot.max()));
                    if (one != null) {
                        values.add(one);
                    }
                }
            }
        }
        Double median = median(values);
        Double mad = median == null ? null : median(deviations(values, median));
        Set<String> flags = values.size() < PROVEN ? Set.of("unproven_baseline") : Set.of();
        return new Baseline(median, mad, values.size(), events, p99, flags);
    }

    private boolean histogram(String name) {
        MetricSpec metric = catalogue.metric(name);
        return metric != null && metric.histogram();
    }

    private static List<Long> valuedAt(List<BaselineSlot> slots) {
        List<Long> timestamps = new ArrayList<>();
        for (BaselineSlot slot : slots) {
            if (slot.present()) {
                timestamps.add(slot.ts());
            }
        }
        return timestamps;
    }

    private static double stat(BaselineSlot slot, String stat) {
        return switch (stat) {
            case "min" -> slot.min();
            case "max" -> slot.max();
            case "sum" -> slot.sum();
            case "count" -> slot.count();
            default -> slot.avg();
        };
    }

    static Double median(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2d;
    }

    private static List<Double> deviations(List<Double> values, double center) {
        List<Double> deviations = new ArrayList<>();
        for (double value : values) {
            deviations.add(Math.abs(value - center));
        }
        return deviations;
    }

    private static Double finite(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
    }

    private static double finiteMin(double value) {
        return Double.isInfinite(value) ? 0d : value;
    }

    private static double finiteMax(double value, double min) {
        return Double.isInfinite(value) ? min : value;
    }
}

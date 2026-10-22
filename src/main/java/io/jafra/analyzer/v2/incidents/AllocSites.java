package io.jafra.analyzer.v2.incidents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.jafra.analyzer.v2.frames.FrameService;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;
import io.jafra.analyzer.v2.store.V2Store.SampleRow;

/** Shared allocation where/who for long_pause and heap_near_full. */
final class AllocSites {
    static final List<String> SOURCE_EVENTS = List.of(
            "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB");

    private AllocSites() {}

    /** Top 5 alloc sites over the last 12 windows. Empty when there are no samples. */
    static List<WhereFrame> where(WindowInput input) {
        Map<String, Double> weight = new LinkedHashMap<>();
        double total = 0d;
        for (SampleRow sample : input.samples().recent("alloc", 12)) {
            if (sample.weight() <= 0d) {
                continue;
            }
            String site = FrameService.allocationSite(input.samples().stack(sample.stackId()));
            if (site == null || site.isBlank()) {
                continue;
            }
            weight.merge(site, sample.weight(), Double::sum);
            total += sample.weight();
        }
        if (total <= 0d) {
            return List.of();
        }
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(weight.entrySet());
        ranked.sort(Comparator.comparingDouble((Map.Entry<String, Double> entry) -> entry.getValue()).reversed());
        List<WhereFrame> frames = new ArrayList<>();
        for (Map.Entry<String, Double> entry : ranked) {
            if (frames.size() >= 5) {
                break;
            }
            frames.add(new WhereFrame(entry.getKey(), 100d * entry.getValue() / total, SOURCE_EVENTS));
        }
        return frames;
    }

    /** Top class of alloc.sampled.bytes. Null when this JVM has no class breakdown. */
    static Who allocatingClass(WindowInput input) {
        List<RollupRow> rows = input.window().breakdown("alloc.sampled.bytes", "class");
        if (rows.isEmpty()) {
            rows = input.window().recentBreakdown("alloc.sampled.bytes", "class", 12);
        }
        RollupRow top = null;
        double keySum = 0d;
        for (RollupRow row : rows) {
            keySum += row.sum();
            if (top == null || row.sum() > top.sum()) {
                top = row;
            }
        }
        if (top == null || top.sum() <= 0d) {
            return null;
        }
        double total = input.window()
                .total("alloc.sampled.bytes")
                .filter(row -> row.count() > 0 && row.sum() > 0d)
                .map(RollupRow::sum)
                .orElse(keySum);
        if (total <= 0d) {
            return null;
        }
        return new Who("class", className(top.key()), 100d * top.sum() / total, true);
    }

    private static String className(String key) {
        if (key == null || key.isBlank()) {
            return "unknown";
        }
        int eq = key.indexOf('=');
        if (eq >= 0 && eq + 1 < key.length()) {
            return key.substring(eq + 1);
        }
        return key;
    }
}

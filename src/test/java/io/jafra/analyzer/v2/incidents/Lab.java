package io.jafra.analyzer.v2.incidents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.jafra.analyzer.v2.rollup.Frame;
import io.jafra.analyzer.v2.store.V2Store.EventRow;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;
import io.jafra.analyzer.v2.store.V2Store.SampleRow;

/** Synthetic window for detector tests. */
final class Lab {
    int jdk = 21;
    int jvmId = 1;
    long jvmStartMs = 0L;
    long fromMs = 0L;
    long toMs = 5_000L;
    String profile = "low";
    String settings = "{\"jvm\":{\"arguments\":\"\"}}";
    final Map<String, Double> avg = new LinkedHashMap<>();
    final Map<String, Double> max = new LinkedHashMap<>();
    final Map<String, Double> sum = new LinkedHashMap<>();
    final Map<String, List<RollupRow>> breakdowns = new LinkedHashMap<>();
    final List<EventRow> events = new ArrayList<>();
    final List<SampleRow> samples = new ArrayList<>();
    final Map<Long, List<Frame>> stacks = new LinkedHashMap<>();
    Baseline baseline = new Baseline(1d, 0.1, 80, 80, null, Set.of());
    boolean nullEvents;
    boolean nullWindow;
    boolean nullBaselines;

    Lab at(long fromMs) {
        Lab copy = new Lab();
        copy.jdk = jdk;
        copy.jvmId = jvmId;
        copy.jvmStartMs = jvmStartMs;
        copy.profile = profile;
        copy.settings = settings;
        copy.avg.putAll(avg);
        copy.max.putAll(max);
        copy.sum.putAll(sum);
        copy.breakdowns.putAll(breakdowns);
        copy.events.addAll(events);
        copy.samples.addAll(samples);
        copy.stacks.putAll(stacks);
        copy.baseline = baseline;
        copy.nullEvents = nullEvents;
        copy.nullWindow = nullWindow;
        copy.nullBaselines = nullBaselines;
        copy.fromMs = fromMs;
        copy.toMs = fromMs + 5_000L;
        return copy;
    }

    Lab jdk(int jdk) {
        this.jdk = jdk;
        return this;
    }

    Lab settings(String settings) {
        this.settings = settings;
        return this;
    }

    Lab metric(String name, double value) {
        avg.put(name, value);
        max.put(name, value);
        sum.put(name, value);
        return this;
    }

    Lab max(String name, double value) {
        max.put(name, value);
        return this;
    }

    Lab sum(String name, double value) {
        sum.put(name, value);
        return this;
    }

    Lab baseline(Baseline baseline) {
        this.baseline = baseline;
        return this;
    }

    WindowInput build() {
        JvmRow jvm = new JvmRow(jvmId, "c", "ns", "pod", "uid", "app", jvmStartMs, jdk, Integer.toString(jdk), profile, 0L, toMs, 1, settings);
        return new WindowInput(
                jvm,
                fromMs,
                toMs,
                nullWindow ? null : new Totals(avg, max, sum, breakdowns),
                new Samples(samples, stacks),
                nullEvents ? null : new Events(events),
                nullBaselines ? null : (name, key, group, stat) -> baseline,
                new JvmSettings(jvm));
    }

    static WindowInput copyAt(Lab source, long fromMs) {
        Lab next = new Lab();
        next.jdk = source.jdk;
        next.jvmId = source.jvmId;
        next.jvmStartMs = source.jvmStartMs;
        next.profile = source.profile;
        next.settings = source.settings;
        next.avg.putAll(source.avg);
        next.max.putAll(source.max);
        next.sum.putAll(source.sum);
        next.breakdowns.putAll(source.breakdowns);
        next.events.addAll(source.events);
        next.samples.addAll(source.samples);
        next.stacks.putAll(source.stacks);
        next.baseline = source.baseline;
        next.nullEvents = source.nullEvents;
        next.nullWindow = source.nullWindow;
        next.nullBaselines = source.nullBaselines;
        return next.at(fromMs).build();
    }

    private record Totals(
            Map<String, Double> avg, Map<String, Double> max, Map<String, Double> sum, Map<String, List<RollupRow>> breakdowns)
            implements RollupView {
        @Override
        public Optional<RollupRow> total(String metric) {
            if (!avg.containsKey(metric) && !max.containsKey(metric) && !sum.containsKey(metric)) {
                return Optional.empty();
            }
            double a = avg.getOrDefault(metric, 0d);
            double m = max.getOrDefault(metric, a);
            double s = sum.getOrDefault(metric, a);
            return Optional.of(new RollupRow(1, metric, "", "", 0L, a, m, a, 1L, s));
        }

        @Override
        public List<RollupRow> breakdown(String metric, String by) {
            return breakdowns.getOrDefault(metric + "|" + by, List.of());
        }

        @Override
        public Map<Integer, Long> hist(String metric, String key) {
            return Map.of();
        }

        @Override
        public Optional<RollupRow> lastBefore(String metric) {
            return Optional.empty();
        }
    }

    private record Samples(List<SampleRow> rows, Map<Long, List<Frame>> stacks) implements SampleView {
        @Override
        public List<SampleRow> rows(String kind) {
            return rows.stream().filter(row -> kind.equals(row.kind())).toList();
        }

        @Override
        public List<SampleRow> recent(String kind, int windows) {
            return rows(kind);
        }

        @Override
        public List<Frame> stack(long stackId) {
            return stacks.getOrDefault(stackId, List.of());
        }
    }

    private record Events(List<EventRow> rows) implements EventView {
        @Override
        public List<EventRow> of(String type) {
            return rows.stream().filter(row -> type.equals(row.type())).toList();
        }

        @Override
        public List<EventRow> internal(String name) {
            return rows.stream().filter(row -> name.equals(row.type())).toList();
        }
    }
}

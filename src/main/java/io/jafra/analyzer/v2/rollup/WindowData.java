package io.jafra.analyzer.v2.rollup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One 5 s file's readings. Tests build this without a JFR file.
 * Breakdown keys keep at most 20 values per (metric, by); the rest fold into {@code __other__}.
 */
public final class WindowData {
    public static final int MAX_BREAKDOWN_KEYS = 20;
    public static final String OTHER = "__other__";

    public String clusterId = "";
    public String namespace = "";
    public String podName = "";
    public String podUid = "";
    public String containerName = "";
    public long jvmStartMs;
    public int jdkMajor;
    public String jdkVersion = "";
    public String profile = "custom";
    public String settingsJson = "{}";
    /** Runs after counter rates are applied and before rows are stored. */
    public Runnable derive = () -> {};
    public long fromMs;
    public long toMs;
    public double dtSeconds = 5d;

    /** Sum of GC {@code sumOfPauses} in microseconds, for gc.pause.time_pct. */
    public double gcSumOfPausesUs;
    /** Sum of safepoint durations in microseconds, for safepoint.time_pct. */
    public double safepointSumUs;
    /** GC CPU time in seconds inside the window. */
    public double gcCpuSeconds;
    public Double rssBytes;
    public Double heapCommittedBytes;
    public Double metaspaceCommittedBytes;
    public Double codeCacheUsedBytes;
    public Double nmtCommittedBytes;
    public Double containerUsageBytes;
    public int methodTraceCount;
    public final Map<String, Integer> methodTracesByHandler = new LinkedHashMap<>();
    public final Map<String, Double> allocWeights = new LinkedHashMap<>();
    public double allocWeightTotal;

    private final Map<SeriesKey, Accum> series = new TreeMap<>();
    private final Map<HistKey, Long> hist = new TreeMap<>();
    private final Map<String, Double> histMin = new LinkedHashMap<>();
    private final Map<String, Double> histMax = new LinkedHashMap<>();
    private final List<CounterObs> counters = new ArrayList<>();
    private final List<StoredEvent> events = new ArrayList<>();
    private final List<StoredEvent> internalEvents = new ArrayList<>();
    private final Map<Long, String> stacks = new LinkedHashMap<>();
    private final Map<SampleKey, SampleAccum> samples = new LinkedHashMap<>();

    public void add(String metric, String byName, String key, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return;
        }
        series.computeIfAbsent(new SeriesKey(metric, blank(byName), blank(key)), ignored -> new Accum()).add(value);
    }

    public void setSpan(String metric, String byName, String key, double min, double max, double avg) {
        Accum accum = new Accum();
        accum.min = min;
        accum.max = max;
        accum.sum = avg;
        accum.count = 1;
        series.put(new SeriesKey(metric, blank(byName), blank(key)), accum);
    }

    public void addHist(String metric, String key, double microseconds) {
        if (Double.isNaN(microseconds) || Double.isInfinite(microseconds) || microseconds < 0d) {
            return;
        }
        String storedKey = blank(key);
        hist.merge(new HistKey(metric, storedKey, LogHistogram.bucket(microseconds)), 1L, Long::sum);
        String extreme = metric + "\0" + storedKey;
        histMin.merge(extreme, microseconds, Math::min);
        histMax.merge(extreme, microseconds, Math::max);
        add(metric, byOf(storedKey), storedKey, microseconds);
    }

    public void counter(String metric, String byName, String key, double cumulative) {
        counters.add(new CounterObs(metric, blank(byName), blank(key), cumulative));
    }

    public void event(StoredEvent event) {
        events.add(event);
    }

    public void internalEvent(StoredEvent event) {
        internalEvents.add(event);
    }

    public void stack(long stackId, String framesJson) {
        stacks.putIfAbsent(stackId, framesJson);
    }

    public void sample(String kind, long stackId, String thread, String state, String handler, long count, double weight) {
        SampleKey key = new SampleKey(kind, stackId, blank(thread), blank(state), blank(handler));
        samples.computeIfAbsent(key, ignored -> new SampleAccum()).add(count, weight);
    }

    public void allocWeight(String byName, String value, double weight) {
        if (weight <= 0d || value == null || value.isBlank()) {
            return;
        }
        allocWeights.merge(byName + "=" + value, weight, Double::sum);
    }

    public Double value(String metric) {
        Accum accum = series.get(new SeriesKey(metric, "", ""));
        if (accum == null || accum.count == 0) {
            return null;
        }
        return accum.avg();
    }

    public List<CounterObs> counters() {
        return counters;
    }

    public Map<SeriesKey, Accum> series() {
        return series;
    }

    public Map<HistKey, Long> hist() {
        return hist;
    }

    public double histMin(String metric, String key) {
        Double value = histMin.get(metric + "\0" + blank(key));
        return value == null ? 0d : value;
    }

    public double histMax(String metric, String key) {
        Double value = histMax.get(metric + "\0" + blank(key));
        return value == null ? 0d : value;
    }

    public List<StoredEvent> events() {
        return events;
    }

    public List<StoredEvent> internalEvents() {
        return internalEvents;
    }

    public Map<Long, String> stacks() {
        return stacks;
    }

    public Map<SampleKey, SampleAccum> samples() {
        return samples;
    }

    /** Folds breakdown keys to the top 20 by count. Hist keys use {@code <by>=__other__}. */
    public void compact() {
        compactSeries();
        compactHist();
        capErrors();
    }

    private void compactSeries() {
        Map<String, List<SeriesKey>> groups = new LinkedHashMap<>();
        for (SeriesKey key : series.keySet()) {
            if (key.byName().isEmpty()) {
                continue;
            }
            groups.computeIfAbsent(key.metric() + "\0" + key.byName(), ignored -> new ArrayList<>()).add(key);
        }
        for (List<SeriesKey> keys : groups.values()) {
            if (keys.size() <= MAX_BREAKDOWN_KEYS) {
                continue;
            }
            keys.sort(Comparator.comparingLong((SeriesKey key) -> series.get(key).count).reversed());
            SeriesKey keepProbe = keys.get(0);
            String otherKey = keepProbe.byName() + "=" + OTHER;
            Accum other = series.computeIfAbsent(
                    new SeriesKey(keepProbe.metric(), keepProbe.byName(), otherKey), ignored -> new Accum());
            for (int i = MAX_BREAKDOWN_KEYS; i < keys.size(); i++) {
                SeriesKey drop = keys.get(i);
                if (drop.key().equals(otherKey)) {
                    continue;
                }
                other.add(series.remove(drop));
            }
        }
    }

    private void compactHist() {
        Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
        for (Map.Entry<HistKey, Long> entry : hist.entrySet()) {
            HistKey key = entry.getKey();
            if (key.key().isEmpty()) {
                continue;
            }
            String by = byOf(key.key());
            counts.computeIfAbsent(key.metric() + "\0" + by, ignored -> new LinkedHashMap<>())
                    .merge(key.key(), entry.getValue(), Long::sum);
        }
        for (Map.Entry<String, Map<String, Long>> group : counts.entrySet()) {
            if (group.getValue().size() <= MAX_BREAKDOWN_KEYS) {
                continue;
            }
            List<Map.Entry<String, Long>> ranked = new ArrayList<>(group.getValue().entrySet());
            ranked.sort(Map.Entry.<String, Long>comparingByValue().reversed());
            String metric = group.getKey().substring(0, group.getKey().indexOf('\0'));
            String by = group.getKey().substring(group.getKey().indexOf('\0') + 1);
            String otherKey = by + "=" + OTHER;
            for (int i = MAX_BREAKDOWN_KEYS; i < ranked.size(); i++) {
                String dropKey = ranked.get(i).getKey();
                if (dropKey.equals(otherKey)) {
                    continue;
                }
                List<HistKey> drops = new ArrayList<>();
                for (HistKey key : hist.keySet()) {
                    if (key.metric().equals(metric) && key.key().equals(dropKey)) {
                        drops.add(key);
                    }
                }
                for (HistKey drop : drops) {
                    long count = hist.remove(drop);
                    hist.merge(new HistKey(metric, otherKey, drop.bucket()), count, Long::sum);
                }
            }
        }
    }

    private void capErrors() {
        List<StoredEvent> errors = new ArrayList<>();
        for (StoredEvent event : events) {
            if ("error".equals(event.type())) {
                errors.add(event);
            }
        }
        if (errors.size() <= 200) {
            return;
        }
        errors.sort(Comparator.comparingLong(StoredEvent::tsMs));
        List<StoredEvent> keep = errors.subList(errors.size() - 200, errors.size());
        events.removeIf(event -> "error".equals(event.type()) && !keep.contains(event));
    }

    private static String byOf(String key) {
        int eq = key.indexOf('=');
        if (eq <= 0) {
            return "";
        }
        return key.substring(0, eq);
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    public record SeriesKey(String metric, String byName, String key) implements Comparable<SeriesKey> {
        @Override
        public int compareTo(SeriesKey other) {
            int cmp = metric.compareTo(other.metric);
            if (cmp != 0) {
                return cmp;
            }
            cmp = byName.compareTo(other.byName);
            if (cmp != 0) {
                return cmp;
            }
            return key.compareTo(other.key);
        }
    }

    public record HistKey(String metric, String key, int bucket) implements Comparable<HistKey> {
        @Override
        public int compareTo(HistKey other) {
            int cmp = metric.compareTo(other.metric);
            if (cmp != 0) {
                return cmp;
            }
            cmp = key.compareTo(other.key);
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(bucket, other.bucket);
        }
    }

    public static final class Accum {
        public double min = Double.POSITIVE_INFINITY;
        public double max = Double.NEGATIVE_INFINITY;
        public double sum;
        public long count;

        public void add(double value) {
            min = Math.min(min, value);
            max = Math.max(max, value);
            sum += value;
            count++;
        }

        public void add(Accum other) {
            if (other == null || other.count == 0) {
                return;
            }
            min = Math.min(min, other.min);
            max = Math.max(max, other.max);
            sum += other.sum;
            count += other.count;
        }

        public double avg() {
            return count == 0 ? 0d : sum / count;
        }
    }

    public record CounterObs(String metric, String byName, String key, double value) {}

    public record StoredEvent(
            String type,
            long tsMs,
            Long durationUs,
            String thread,
            String topFrame,
            Long stackId,
            String fieldsJson) {}

    public record SampleKey(String kind, long stackId, String thread, String state, String handler) {}

    public static final class SampleAccum {
        public long count;
        public double weight;

        void add(long extraCount, double extraWeight) {
            count += extraCount;
            weight += extraWeight;
        }
    }
}

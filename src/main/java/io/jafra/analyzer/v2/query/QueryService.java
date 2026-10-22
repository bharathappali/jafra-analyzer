package io.jafra.analyzer.v2.query;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jafra.analyzer.recordings.ReportWindow;
import io.jafra.analyzer.v2.api.V2Exception;
import io.jafra.analyzer.v2.api.V2Range;
import io.jafra.analyzer.v2.api.V2Times;
import io.jafra.analyzer.v2.catalogue.Catalogue;
import io.jafra.analyzer.v2.catalogue.MetricSpec;
import io.jafra.analyzer.v2.rollup.ChunkReader;
import io.jafra.analyzer.v2.rollup.LogHistogram;
import io.jafra.analyzer.v2.store.V2Store;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

@ApplicationScoped
public class QueryService {
    static final long[] STEP_MS = {
        5_000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 900_000,
        1_800_000, 3_600_000, 7_200_000, 21_600_000
    };
    static final String[] STEP_NAME = {
        "5s", "10s", "15s", "30s", "1m", "2m", "5m", "10m", "15m", "30m", "1h", "2h", "6h"
    };
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int POINT_CAP = 60;
    private static final int BY_STEP_KEYS = 3;

    private final V2Store store;
    private final Catalogue catalogue;

    @Inject
    public QueryService(V2Store store, Catalogue catalogue) {
        this.store = store;
        this.catalogue = catalogue;
    }

    public QueryResponse query(
            JvmRow jvm,
            String metricName,
            String statName,
            String by,
            String step,
            String where,
            Integer top,
            String last,
            String from,
            String to) {
        MetricSpec metric = requireMetric(metricName, jvm);
        String stat = statName == null || statName.isBlank() ? "avg" : statName.trim();
        if (!metric.stats().contains(stat) && !percentile(stat)) {
            throw V2Exception.unsupportedStat(stat + " is not a stat of " + metric.name(), metric.stats());
        }
        if (percentile(stat) && !metric.histogram()) {
            throw V2Exception.unsupportedStat(stat + " is only available on histogram metrics", metric.stats());
        }
        if (by != null && !by.isBlank() && !metric.by().contains(by)) {
            throw V2Exception.unsupportedBy(by + " is not a breakdown of " + metric.name(), metric.by());
        }
        int limit = top == null ? 10 : Math.min(10, Math.max(1, top));
        Instant end = Instant.ofEpochMilli(Math.max(jvm.lastMs(), jvm.firstMs()));
        V2Range range = V2Range.parse(last, from, to, end);
        long fromMs = range.from().toEpochMilli();
        long toMs = range.to().toEpochMilli();
        List<String> flags = flags(metric, jvm);
        try {
            if (percentile(stat)) {
                return percentile(jvm, metric, stat, where, range, flags);
            }
            boolean stepped = step != null && !step.isBlank();
            boolean broken = by != null && !by.isBlank();
            if (stepped && broken) {
                return byStep(jvm, metric, stat, by, step, where, range, flags);
            }
            if (stepped) {
                return series(jvm, metric, stat, step, where, range, flags, "");
            }
            if (broken) {
                return breakdown(jvm, metric, stat, by, where, range, flags, limit);
            }
            return scalar(jvm, metric, stat, where, range, flags);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    public MetricSpec requireMetric(String name, JvmRow jvm) {
        if (name == null || name.isBlank() || catalogue.metric(name) == null) {
            List<String> valid = catalogue.closestMetrics(name, 3);
            if (catalogue.trendMetrics().stream().anyMatch(metric -> metric.name().equals(name))) {
                valid = catalogue.trendMetrics().stream().map(MetricSpec::name).toList();
            }
            throw V2Exception.unknownMetric(
                    name + " is not in the catalogue", valid);
        }
        MetricSpec metric = catalogue.metric(name);
        if (jvm != null && !metric.onJdk(jvm.jdkMajor())) {
            throw V2Exception.notOnJdk("needs JDK " + jdks(metric));
        }
        if (metric.optInNmt() && !nmtOn(jvm)) {
            throw V2Exception.nmtOff();
        }
        return metric;
    }

    private QueryResponse scalar(
            JvmRow jvm, MetricSpec metric, String stat, String where, V2Range range, List<String> flags)
            throws IOException {
        List<RollupRow> rows = rows(jvm, metric.name(), false, from(range), to(range), where, null);
        if (rows.isEmpty()) {
            throw noData(jvm, metric.name(), from(range));
        }
        Acc acc = acc(rows);
        return QueryResponse.scalar(
                statValue(acc, stat),
                metric.unit(),
                window(range, null),
                acc.count,
                flags,
                stat);
    }

    private QueryResponse series(
            JvmRow jvm,
            MetricSpec metric,
            String stat,
            String step,
            String where,
            V2Range range,
            List<String> flags,
            String keyLabel)
            throws IOException {
        long requested = parseStep(step);
        long used = coarsen(requested, to(range) - from(range), POINT_CAP);
        boolean minute = used >= 60_000 && used % 60_000 == 0;
        List<RollupRow> rows = rows(jvm, metric.name(), minute, from(range), to(range), where, null);
        if (rows.isEmpty()) {
            throw noData(jvm, metric.name(), from(range));
        }
        List<QueryPoint> points = points(rows, used, stat);
        long n = rows.stream().mapToLong(RollupRow::count).sum();
        return QueryResponse.series(
                metric.unit(),
                window(range, nameOf(used)),
                n,
                flags,
                stat,
                nameOf(used),
                points,
                summary(points),
                keyLabel);
    }

    private QueryResponse breakdown(
            JvmRow jvm, MetricSpec metric, String stat, String by, String where, V2Range range, List<String> flags, int limit)
            throws IOException {
        List<RollupRow> rows = rows(jvm, metric.name(), false, from(range), to(range), where, by);
        if (rows.isEmpty()) {
            throw noData(jvm, metric.name(), from(range));
        }
        Map<String, Acc> grouped = new LinkedHashMap<>();
        for (RollupRow row : rows) {
            grouped.computeIfAbsent(row.key(), ignored -> new Acc()).add(row);
        }
        List<Map.Entry<String, Acc>> ranked = new ArrayList<>(grouped.entrySet());
        ranked.sort(Comparator.comparingDouble((Map.Entry<String, Acc> entry) -> statValue(entry.getValue(), stat)).reversed());
        double grand = 0;
        for (Map.Entry<String, Acc> entry : ranked) {
            grand += statValue(entry.getValue(), stat);
        }
        List<BreakdownRow> out = new ArrayList<>();
        double shown = 0;
        int n = 0;
        long samples = 0;
        for (Map.Entry<String, Acc> entry : ranked) {
            samples += entry.getValue().count;
            double value = statValue(entry.getValue(), stat);
            if (out.size() >= limit) {
                continue;
            }
            shown += value;
            double share = grand == 0d ? 0d : 100d * value / grand;
            out.add(new BreakdownRow(entry.getKey(), value, share));
            n++;
        }
        double other = grand == 0d ? 0d : 100d * (grand - shown) / grand;
        return QueryResponse.breakdown(metric.unit(), window(range, null), samples, flags, stat, out, other, grand);
    }

    private QueryResponse byStep(
            JvmRow jvm, MetricSpec metric, String stat, String by, String step, String where, V2Range range, List<String> flags)
            throws IOException {
        List<RollupRow> rows = rows(jvm, metric.name(), false, from(range), to(range), where, by);
        if (rows.isEmpty()) {
            throw noData(jvm, metric.name(), from(range));
        }
        Map<String, Double> rank = new LinkedHashMap<>();
        for (RollupRow row : rows) {
            rank.merge(row.key(), row.sum(), Double::sum);
        }
        List<String> keys = new ArrayList<>(rank.keySet());
        keys.sort(Comparator.comparingDouble((String key) -> rank.get(key)).reversed());
        if (keys.size() > BY_STEP_KEYS) {
            keys = keys.subList(0, BY_STEP_KEYS);
        }
        long used = coarsen(parseStep(step), to(range) - from(range), POINT_CAP);
        List<KeyedSeries> series = new ArrayList<>();
        long samples = 0;
        for (String key : keys) {
            List<RollupRow> matching = new ArrayList<>();
            for (RollupRow row : rows) {
                if (key.equals(row.key())) {
                    matching.add(row);
                    samples += row.count();
                }
            }
            series.add(new KeyedSeries(key, points(matching, used, stat)));
        }
        return QueryResponse.byStep(
                metric.unit(), window(range, nameOf(used)), samples, flags, stat, nameOf(used), series);
    }

    private QueryResponse percentile(
            JvmRow jvm, MetricSpec metric, String stat, String where, V2Range range, List<String> flags)
            throws IOException {
        String key = where == null || where.isBlank() ? "" : where.trim();
        Map<Integer, Long> buckets = store.readHist(jvm.jvmId(), metric.name(), key, from(range), to(range));
        if (buckets.isEmpty()) {
            throw noData(jvm, metric.name(), from(range));
        }
        List<RollupRow> rows = rows(jvm, metric.name(), false, from(range), to(range), key.isEmpty() ? null : key, null);
        double min = rows.stream().mapToDouble(RollupRow::min).min().orElse(0d);
        double max = rows.stream().mapToDouble(RollupRow::max).max().orElse(min);
        double q = switch (stat) {
            case "p50" -> 0.50;
            case "p95" -> 0.95;
            default -> 0.99;
        };
        long n = buckets.values().stream().mapToLong(Long::longValue).sum();
        double value = LogHistogram.percentile(buckets, q, min, max);
        return QueryResponse.scalar(value, metric.unit(), window(range, null), n, flags, stat);
    }

    private List<RollupRow> rows(
            JvmRow jvm, String metric, boolean minute, long fromMs, long toMs, String where, String by)
            throws IOException {
        String key = where == null || where.isBlank() ? null : where.trim();
        String byName = key != null ? null : (by == null || by.isBlank() ? "" : by);
        if (key != null && by != null && !by.isBlank()) {
            byName = by;
        }
        if (key == null && (by == null || by.isBlank())) {
            byName = "";
            key = "";
        }
        return store.readRollup(minute, jvm.jvmId(), metric, fromMs, toMs, byName, key);
    }

    private static List<QueryPoint> points(List<RollupRow> rows, long stepMs, String stat) {
        Map<Long, Acc> buckets = new LinkedHashMap<>();
        for (RollupRow row : rows) {
            long bucket = Math.floorDiv(row.ts(), stepMs) * stepMs;
            buckets.computeIfAbsent(bucket, ignored -> new Acc()).add(row);
        }
        List<QueryPoint> points = new ArrayList<>();
        for (Map.Entry<Long, Acc> entry : buckets.entrySet()) {
            points.add(new QueryPoint(V2Times.text(entry.getKey()), statValue(entry.getValue(), stat)));
        }
        return points;
    }

    private static QuerySummary summary(List<QueryPoint> points) {
        if (points.isEmpty()) {
            return null;
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        String minAt = points.get(0).t();
        String maxAt = points.get(0).t();
        List<Double> values = new ArrayList<>();
        for (QueryPoint point : points) {
            values.add(point.v());
            if (point.v() < min) {
                min = point.v();
                minAt = point.t();
            }
            if (point.v() > max) {
                max = point.v();
                maxAt = point.t();
            }
        }
        values.sort(Double::compare);
        double median;
        int size = values.size();
        if (size % 2 == 1) {
            median = values.get(size / 2);
        } else {
            median = (values.get(size / 2 - 1) + values.get(size / 2)) / 2d;
        }
        return new QuerySummary(points.get(0).v(), points.get(points.size() - 1).v(), min, minAt, max, maxAt, median);
    }

    private V2Exception noData(JvmRow jvm, String metric, long around) throws IOException {
        Long nearest = store.nearestTs(jvm.jvmId(), metric, around);
        return V2Exception.noData("no data in this range", nearest == null ? null : V2Times.text(nearest));
    }

    public static long parseStep(String step) {
        try {
            return ReportWindow.parseLast(step).toMillis();
        } catch (IllegalArgumentException error) {
            throw V2Exception.badRange(error.getMessage());
        }
    }

    public static long coarsen(long requestedMs, long rangeMs, int cap) {
        long chosen = STEP_MS[STEP_MS.length - 1];
        boolean seen = false;
        for (long candidate : STEP_MS) {
            if (candidate < requestedMs) {
                continue;
            }
            seen = true;
            chosen = candidate;
            if (pointCount(rangeMs, candidate) <= cap) {
                return candidate;
            }
        }
        if (!seen) {
            chosen = requestedMs;
        }
        if (pointCount(rangeMs, chosen) <= cap) {
            return chosen;
        }
        return Math.max(chosen, (rangeMs + cap - 1) / cap);
    }

    public static String nameOf(long stepMs) {
        for (int i = 0; i < STEP_MS.length; i++) {
            if (STEP_MS[i] == stepMs) {
                return STEP_NAME[i];
            }
        }
        return stepMs + "ms";
    }

    private static long pointCount(long rangeMs, long stepMs) {
        if (stepMs <= 0) {
            return capSafe(rangeMs);
        }
        return Math.max(1, (rangeMs + stepMs - 1) / stepMs);
    }

    private static long capSafe(long rangeMs) {
        return rangeMs;
    }

    public static List<String> flags(MetricSpec metric, JvmRow jvm) {
        Set<String> flags = new LinkedHashSet<>();
        if (metric.flags() != null) {
            flags.addAll(metric.flags());
        }
        if ("async-profiler".equals(metric.producer())) {
            flags.add("estimate");
        }
        if (thresholdMs(jvm, metric.sourceEvent()) > 0d) {
            flags.add("lower_bound");
        }
        if ("low".equals(jvm.profile())
                && ("safepoint.duration.us".equals(metric.name()) || "safepoint.time_pct".equals(metric.name()))) {
            flags.add("lower_bound");
        }
        return List.copyOf(flags);
    }

    public static boolean nmtOn(JvmRow jvm) {
        if (jvm == null || jvm.settings() == null) {
            return false;
        }
        try {
            return "enabled".equals(JSON.readTree(jvm.settings()).path("nmt").asText());
        } catch (IOException error) {
            return false;
        }
    }

    private static double thresholdMs(JvmRow jvm, String event) {
        if (jvm == null || event == null || jvm.settings() == null) {
            return 0d;
        }
        try {
            JsonNode node = JSON.readTree(jvm.settings()).path("recording").path("thresholds").path(event);
            if (node.isMissingNode() || node.isNull()) {
                return 0d;
            }
            Double parsed = ChunkReader.thresholdMs(node.asText());
            return parsed == null ? 0d : parsed;
        } catch (IOException error) {
            return 0d;
        }
    }

    private static String jdks(MetricSpec metric) {
        List<String> parts = new ArrayList<>();
        for (int jdk : metric.jdk()) {
            parts.add(Integer.toString(jdk));
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(" or ", parts);
    }

    private static boolean percentile(String stat) {
        return "p50".equals(stat) || "p95".equals(stat) || "p99".equals(stat);
    }

    private static long from(V2Range range) {
        return range.from().toEpochMilli();
    }

    private static long to(V2Range range) {
        return range.to().toEpochMilli();
    }

    private static RangeWindow window(V2Range range, String step) {
        return new RangeWindow(V2Times.text(range.from()), V2Times.text(range.to()), step);
    }

    private static Acc acc(List<RollupRow> rows) {
        Acc acc = new Acc();
        for (RollupRow row : rows) {
            acc.add(row);
        }
        return acc;
    }

    private static double statValue(Acc acc, String stat) {
        return switch (stat) {
            case "min" -> acc.min;
            case "max" -> acc.max;
            case "sum" -> acc.sum;
            case "count" -> acc.count;
            default -> acc.count == 0 ? 0d : acc.sum / acc.count;
        };
    }

    private static final class Acc {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum;
        long count;

        void add(RollupRow row) {
            min = Math.min(min, row.min());
            max = Math.max(max, row.max());
            sum += row.sum();
            count += row.count();
        }
    }
}

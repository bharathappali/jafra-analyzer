package io.jafra.analyzer.v2.trend;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import io.jafra.analyzer.v2.api.V2Exception;
import io.jafra.analyzer.v2.api.V2Range;
import io.jafra.analyzer.v2.api.V2Times;
import io.jafra.analyzer.v2.catalogue.Catalogue;
import io.jafra.analyzer.v2.catalogue.MetricSpec;
import io.jafra.analyzer.v2.query.QueryService;
import io.jafra.analyzer.v2.store.V2Store;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

@ApplicationScoped
public class TrendService {
    private static final int CAP = 360;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final V2Store store;
    private final Catalogue catalogue;

    @Inject
    public TrendService(V2Store store, Catalogue catalogue) {
        this.store = store;
        this.catalogue = catalogue;
    }

    public Trend trend(JvmRow jvm, String name, String last, String from, String to) {
        MetricSpec metric = catalogue.metric(name);
        if (metric == null || !metric.trend()) {
            throw V2Exception.unsupportedStat(
                    name + " is not a trend metric",
                    catalogue.trendMetrics().stream().map(MetricSpec::name).toList());
        }
        if (!metric.onJdk(jvm.jdkMajor())) {
            throw V2Exception.notOnJdk("needs JDK " + metric.jdk());
        }
        if (metric.optInNmt() && !QueryService.nmtOn(jvm)) {
            throw V2Exception.nmtOff();
        }
        String lastArg = last == null && (from == null || from.isBlank()) && (to == null || to.isBlank()) ? "60m" : last;
        Instant end = Instant.ofEpochMilli(Math.max(jvm.lastMs(), jvm.firstMs()));
        V2Range range = V2Range.parse(lastArg, from, to, end);
        try {
            List<RollupRow> rows = store.readRollup(
                    false, jvm.jvmId(), metric.name(), range.from().toEpochMilli(), range.to().toEpochMilli(), "", "");
            long[] times = new long[rows.size()];
            double[] values = new double[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                times[i] = rows.get(i).ts();
                values[i] = rows.get(i).avg();
            }
            if (values.length > CAP) {
                long step = QueryService.coarsen(5_000, range.to().toEpochMilli() - range.from().toEpochMilli(), CAP);
                double[][] buckets = bucket(times, values, step);
                times = new long[buckets.length];
                values = new double[buckets.length];
                for (int i = 0; i < buckets.length; i++) {
                    times[i] = (long) buckets[i][0];
                    values[i] = buckets[i][1];
                }
            }
            Limit limit = limit(jvm, metric, range);
            TrendMath.Result result = TrendMath.assess(times, values, limit.value);
            long spanS = times.length == 0 ? 0L : (times[times.length - 1] - times[0]) / 1000L;
            double current = values.length == 0 ? 0d : values[values.length - 1];
            List<String> flags = metric.flags() == null ? List.of() : metric.flags();
            return new Trend(
                    metric.name(),
                    metric.unit(),
                    new TimeWindow(V2Times.text(range.from()), V2Times.text(range.to())),
                    current,
                    limit.value,
                    limit.source,
                    result.slopePerHour(),
                    new SlopeCi(result.slopeLow(), result.slopeHigh()),
                    result.mkP(),
                    result.significant(),
                    result.timeToLimitMin() == null
                            ? null
                            : new TimeToLimit(
                                    result.timeToLimitMin().low(),
                                    result.timeToLimitMin().mid(),
                                    result.timeToLimitMin().high()),
                    result.shape(),
                    result.n(),
                    spanS,
                    flags);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    /**
     * In-memory trend. Fewer than 30 points or a span under 300 s is unknown, with reason
     * {@code not enough data yet (n/30)}. The math matches {@link TrendMath#assess}. Series longer
     * than 360 points are step-averaged first.
     */
    public static Analysis analyze(long[] tMs, double[] y, Double limit) {
        if (tMs == null || y == null || tMs.length != y.length) {
            throw new IllegalArgumentException("times and values differ");
        }
        int n = y.length;
        long span = n == 0 ? 0L : tMs[n - 1] - tMs[0];
        if (n < TrendMath.MIN_POINTS || span < TrendMath.MIN_SPAN_MS) {
            return Analysis.unknown("not enough data yet (" + n + "/30)", n);
        }
        long[] times = tMs;
        double[] values = y;
        if (values.length > 360) {
            long step = QueryService.coarsen(5_000L, Math.max(1L, span), 360);
            double[][] buckets = bucket(times, values, step);
            times = new long[buckets.length];
            values = new double[buckets.length];
            for (int i = 0; i < buckets.length; i++) {
                times[i] = (long) buckets[i][0];
                values[i] = buckets[i][1];
            }
        }
        TrendMath.Result result = TrendMath.assess(times, values, limit);
        return new Analysis(true, null, result);
    }

    public record Analysis(boolean known, String reason, TrendMath.Result result) {
        static Analysis unknown(String reason, int n) {
            return new Analysis(false, reason, new TrendMath.Result(0d, 0d, 0d, 1d, 1d, false, "unknown", null, null, n));
        }
    }

    private Limit limit(JvmRow jvm, MetricSpec metric, V2Range range) throws IOException {
        String named = metric.limit();
        if (named == null) {
            return new Limit(null, null);
        }
        if (named.indexOf('.') > 0) {
            List<RollupRow> rows = store.readRollup(
                    false, jvm.jvmId(), named, range.from().toEpochMilli(), range.to().toEpochMilli(), "", "");
            if (rows.isEmpty()) {
                return new Limit(null, null);
            }
            return new Limit(rows.get(rows.size() - 1).avg(), source(named));
        }
        Double value = flag(jvm, named);
        if (value == null) {
            return new Limit(null, null);
        }
        return new Limit(value, named);
    }

    private static String source(String metric) {
        return switch (metric) {
            case "cpu.limit.cores", "container.limit.bytes" -> "pod limit";
            case "heap.max.bytes" -> "-Xmx";
            case "codecache.reserved.bytes" -> "reserved code cache";
            default -> metric;
        };
    }

    private static Double flag(JvmRow jvm, String name) {
        if (jvm.settings() == null) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(jvm.settings()).path("flags").path(name);
            if (node.isMissingNode() || node.isNull()) {
                return null;
            }
            String text = node.asText();
            if (text.isBlank() || "not set".equalsIgnoreCase(text)) {
                return null;
            }
            return Double.parseDouble(text);
        } catch (IOException | NumberFormatException error) {
            return null;
        }
    }

    private static double[][] bucket(long[] times, double[] values, long step) {
        List<long[]> keys = new ArrayList<>();
        List<double[]> acc = new ArrayList<>();
        for (int i = 0; i < times.length; i++) {
            long bucket = Math.floorDiv(times[i], step) * step;
            if (keys.isEmpty() || keys.get(keys.size() - 1)[0] != bucket) {
                keys.add(new long[] {bucket});
                acc.add(new double[2]);
            }
            double[] sum = acc.get(acc.size() - 1);
            sum[0] += values[i];
            sum[1] += 1;
        }
        double[][] out = new double[keys.size()][2];
        for (int i = 0; i < keys.size(); i++) {
            out[i][0] = keys.get(i)[0];
            out[i][1] = acc.get(i)[0] / acc.get(i)[1];
        }
        return out;
    }

    private record Limit(Double value, String source) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TimeWindow(String from, String to) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TimeToLimit(Double low, double mid, Double high) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SlopeCi(double lo, double hi) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Trend(
            String metric,
            String unit,
            TimeWindow window,
            double current,
            Double limit,
            String limitSource,
            double slopePerH,
            SlopeCi slopeCi95,
            double mkP,
            boolean significant,
            TimeToLimit timeToLimit,
            String shape,
            int n,
            long spanS,
            List<String> flags) {}
}

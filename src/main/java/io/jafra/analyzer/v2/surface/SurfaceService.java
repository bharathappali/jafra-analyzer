package io.jafra.analyzer.v2.surface;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import io.jafra.analyzer.v2.api.V2Exception;
import io.jafra.analyzer.v2.catalogue.ViewSpec;
import io.jafra.analyzer.v2.api.V2Range;
import io.jafra.analyzer.v2.api.V2Times;
import io.jafra.analyzer.v2.catalogue.Catalogue;
import io.jafra.analyzer.v2.incidents.Baseline;
import io.jafra.analyzer.v2.incidents.BaselineService;
import io.jafra.analyzer.v2.catalogue.ChartSpec;
import io.jafra.analyzer.v2.catalogue.MetricSpec;
import io.jafra.analyzer.v2.query.QueryService;
import io.jafra.analyzer.v2.rollup.LogHistogram;
import io.jafra.analyzer.v2.store.V2Store;
import io.jafra.analyzer.v2.store.V2Store.EventRow;
import io.jafra.analyzer.v2.store.V2Store.IncidentRecord;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

/**
 * Workloads, overview, timeline, and charts. Chart views are echoed as given: the catalogue does not
 * map metrics onto views, so every view returns every declared metric.
 */
@ApplicationScoped
public class SurfaceService {
    static final int SERIES_CAP = 360;
    static final int MARKER_CAP = 200;
    static final int PROVEN_BASELINE = 60;

    private final V2Store store;
    private final Catalogue catalogue;
    private final BaselineService baselines;

    @Inject
    public SurfaceService(V2Store store, Catalogue catalogue, BaselineService baselines) {
        this.store = store;
        this.catalogue = catalogue;
        this.baselines = baselines;
    }

    /** Overview lanes for one range. Incident groups are chosen from these. */
    public List<Lane> overviewLanes(JvmRow jvm, long fromMs, long toMs) throws IOException {
        Map<String, String> levels = store.incidentLevels(jvm.jvmId(), fromMs, toMs);
        List<IncidentRecord> incidents = store.incidents(jvm.jvmId());
        List<Lane> lanes = new ArrayList<>();
        for (String group : catalogue.groups()) {
            lanes.add(lane(jvm, group, fromMs, toMs, levels.get(group), incidents));
        }
        return lanes;
    }

    public WorkloadList workloads(String cluster) {
        try {
            List<Workload> rows = new ArrayList<>();
            for (JvmRow jvm : store.allJvms()) {
                if (cluster != null && !cluster.isBlank() && !cluster.equals(jvm.clusterId())) {
                    continue;
                }
                rows.add(workload(jvm));
            }
            return new WorkloadList(rows);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    public Report report(JvmRow jvm, String last, String from, String to) {
        V2Range range = range(jvm, last, from, to);
        long fromMs = range.from().toEpochMilli();
        long toMs = range.to().toEpochMilli();
        try {
            Map<String, String> levels = store.incidentLevels(jvm.jvmId(), fromMs, toMs);
            List<IncidentRecord> incidents = store.incidents(jvm.jvmId());
            List<Lane> lanes = new ArrayList<>();
            for (String group : catalogue.groups()) {
                lanes.add(lane(jvm, group, fromMs, toMs, levels.get(group), incidents));
            }
            lanes.sort(laneOrder());
            return new Report(window(range, null), jvm.jdkMajor(), lanes);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    public ChartList charts(JvmRow jvm) {
        List<ChartCard> cards = new ArrayList<>();
        for (ChartSpec chart : catalogue.charts()) {
            cards.add(card(chart, jvm));
        }
        return new ChartList(cards);
    }

    public ChartData chart(JvmRow jvm, String chartId, String view, String since, String last, String from, String to) {
        ChartSpec chart = catalogue.chart(chartId);
        if (chart == null) {
            throw V2Exception.unknownChart(catalogue.chartIds());
        }
        V2Range range = range(jvm, last, from, to);
        Long sinceMs = since == null || since.isBlank() ? null : V2Times.parse(since, "since").toEpochMilli();
        long used = QueryService.coarsen(5_000, range.to().toEpochMilli() - range.from().toEpochMilli(), SERIES_CAP);
        boolean minute = used >= 60_000 && used % 60_000 == 0;
        String echoed = view == null || view.isBlank() ? chart.views().get(0).id() : view;
        try {
            List<MetricSeries> series = new ArrayList<>();
            for (String name : chart.metrics()) {
                MetricSpec metric = catalogue.metric(name);
                if (metric == null) {
                    continue;
                }
                series.add(series(jvm, metric, range, used, minute, sinceMs));
            }
            List<Marker> markers = markers(jvm, chart.events(), range, sinceMs);
            return new ChartData(
                    chart.id(),
                    chart.title(),
                    echoed,
                    availability(chart, jvm),
                    window(range, QueryService.nameOf(used)),
                    QueryService.nameOf(used),
                    series,
                    markers);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    public Timeline timeline(JvmRow jvm, String last, String from, String to) {
        V2Range range = range(jvm, last, from, to);
        long fromMs = range.from().toEpochMilli();
        long toMs = range.to().toEpochMilli();
        try {
            List<long[]> windows = store.windowBounds(jvm.jvmId(), fromMs, toMs);
            List<IncidentRecord> incidents = store.incidents(jvm.jvmId());
            Map<String, MetricSpec> metrics = overviewByGroup();
            List<TimelineLane> lanes = new ArrayList<>();
            for (String group : catalogue.groups()) {
                List<Span> spans = new ArrayList<>();
                for (long[] window : windows) {
                    Map<String, RollupRow> totals = store.readTotalsAt(jvm.jvmId(), window[0]);
                    String state = windowState(jvm, metrics.get(group), totals, incidents, group, window[0], window[1]);
                    if (!spans.isEmpty() && spans.get(spans.size() - 1).state.equals(state)) {
                        Span lastSpan = spans.remove(spans.size() - 1);
                        spans.add(new Span(lastSpan.from, window[1], state));
                    } else {
                        spans.add(new Span(window[0], window[1], state));
                    }
                }
                lanes.add(new TimelineLane(group, segments(capSpans(spans))));
            }
            List<IncidentRecord> overlapping = new ArrayList<>();
            for (IncidentRecord row : incidents) {
                if (row.t0() <= toMs && (row.t1() == null || row.t1() >= fromMs)) {
                    overlapping.add(row);
                }
            }
            overlapping.sort(Comparator
                    .comparingInt((IncidentRecord row) -> "critical".equals(row.level()) ? 0 : 1)
                    .thenComparing(Comparator.comparingLong(IncidentRecord::t0).reversed()));
            if (overlapping.size() > 20) {
                overlapping = overlapping.subList(0, 20);
            }
            List<TimelineIncident> listed = new ArrayList<>();
            for (IncidentRecord row : overlapping) {
                listed.add(new TimelineIncident(
                        row.id(),
                        row.type(),
                        row.group(),
                        row.level(),
                        V2Times.text(row.t0()),
                        row.t1() == null ? null : V2Times.text(row.t1())));
            }
            return new Timeline(window(range, null), lanes, listed);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private Lane lane(
            JvmRow jvm, String group, long fromMs, long toMs, String incidentLevel, List<IncidentRecord> incidents)
            throws IOException {
        MetricSpec metric = overviewByGroup().get(group);
        List<String> open = openIds(incidents, group, fromMs, toMs);
        String stat = statOf(metric);
        if (metric == null) {
            return new Lane(group, "unavailable", null, stat, null, null, null, null, open, List.of());
        }
        if (!metric.onJdk(jvm.jdkMajor())) {
            return new Lane(group, "unavailable", metric.name(), stat, null, metric.unit(), null, null, open, List.of("not_on_this_jdk"));
        }
        if (metric.optInNmt() && !QueryService.nmtOn(jvm)) {
            return new Lane(group, "unavailable", metric.name(), stat, null, metric.unit(), null, null, open, List.of("nmt_off"));
        }
        Double value = value(jvm, metric, fromMs, toMs);
        if (value == null) {
            return new Lane(group, "unavailable", metric.name(), stat, null, metric.unit(), null, null, open, List.of());
        }
        Baseline baseline = baselines.baseline(jvm.jvmId(), metric.name(), "", group, stat, toMs);
        Double normal = baseline.median();
        boolean unproven = baseline.n() < PROVEN_BASELINE;
        Double vs = vsNormal(value, normal);
        List<String> flags = new ArrayList<>(QueryService.flags(metric, jvm));
        if (unproven) {
            flags.add("unproven_baseline");
        }
        String state = incidentLevel == null ? "ok" : incidentLevel;
        return new Lane(group, state, metric.name(), stat, value, metric.unit(), normal, vs, open, flags);
    }

    private static String statOf(MetricSpec metric) {
        if (metric == null) {
            return null;
        }
        String stat = metric.overviewStat();
        return stat == null || stat.isBlank() ? "avg" : stat;
    }

    private static List<String> openIds(List<IncidentRecord> incidents, String group, long fromMs, long toMs) {
        List<String> ids = new ArrayList<>();
        for (IncidentRecord row : incidents) {
            if (!group.equals(row.group()) || !"open".equals(row.status())) {
                continue;
            }
            if (row.t0() <= toMs && (row.t1() == null || row.t1() >= fromMs)) {
                ids.add(row.id());
            }
        }
        return ids;
    }

    private static String windowState(
            JvmRow jvm,
            MetricSpec metric,
            Map<String, RollupRow> totals,
            List<IncidentRecord> incidents,
            String group,
            long fromMs,
            long toMs) {
        if (metric == null || !metric.onJdk(jvm.jdkMajor()) || (metric.optInNmt() && !QueryService.nmtOn(jvm))) {
            return "unavailable";
        }
        if (!totals.containsKey(metric.name())) {
            return "unavailable";
        }
        String worst = null;
        for (IncidentRecord row : incidents) {
            if (!group.equals(row.group())) {
                continue;
            }
            long end = row.t1() == null ? Long.MAX_VALUE : row.t1();
            if (row.t0() < toMs && end >= fromMs && rank(row.level()) > rank(worst)) {
                worst = row.level();
            }
        }
        return worst == null ? "ok" : worst;
    }

    private static int rank(String level) {
        if ("critical".equals(level)) {
            return 2;
        }
        if ("warning".equals(level)) {
            return 1;
        }
        return 0;
    }

    private static List<Span> capSpans(List<Span> spans) {
        List<Span> capped = new ArrayList<>(spans);
        while (capped.size() > SERIES_CAP) {
            int best = 0;
            long bestLen = Long.MAX_VALUE;
            for (int i = 0; i < capped.size() - 1; i++) {
                long len = capped.get(i).to - capped.get(i).from + capped.get(i + 1).to - capped.get(i + 1).from;
                if (len < bestLen) {
                    bestLen = len;
                    best = i;
                }
            }
            Span left = capped.get(best);
            Span right = capped.get(best + 1);
            String state = right.to - right.from > left.to - left.from ? right.state : left.state;
            capped.set(best, new Span(left.from, right.to, state));
            capped.remove(best + 1);
        }
        return capped;
    }

    private static List<Segment> segments(List<Span> spans) {
        List<Segment> segments = new ArrayList<>();
        for (Span span : spans) {
            segments.add(new Segment(V2Times.text(span.from), V2Times.text(span.to), span.state));
        }
        return segments;
    }

    private record Span(long from, long to, String state) {}

    private Double value(JvmRow jvm, MetricSpec metric, long fromMs, long toMs) throws IOException {
        if ("p99".equals(metric.overviewStat())) {
            Map<Integer, Long> buckets = store.readHist(jvm.jvmId(), metric.name(), "", fromMs, toMs);
            if (buckets.isEmpty()) {
                return null;
            }
            List<RollupRow> rows = totals(jvm, metric.name(), false, fromMs, toMs);
            double min = rows.stream().mapToDouble(RollupRow::min).min().orElse(0d);
            double max = rows.stream().mapToDouble(RollupRow::max).max().orElse(min);
            return LogHistogram.percentile(buckets, 0.99, min, max);
        }
        List<RollupRow> rows = totals(jvm, metric.name(), false, fromMs, toMs);
        if (rows.isEmpty()) {
            return null;
        }
        double sum = 0;
        long count = 0;
        for (RollupRow row : rows) {
            sum += row.sum();
            count += row.count();
        }
        return count == 0 ? null : sum / count;
    }

    private MetricSeries series(JvmRow jvm, MetricSpec metric, V2Range range, long step, boolean minute, Long sinceMs)
            throws IOException {
        List<String> flags = new ArrayList<>();
        if (!metric.onJdk(jvm.jdkMajor())) {
            flags.add("not_on_this_jdk");
            return new MetricSeries(metric.name(), metric.unit(), List.of(), flags);
        }
        if (metric.optInNmt() && !QueryService.nmtOn(jvm)) {
            flags.add("nmt_off");
            return new MetricSeries(metric.name(), metric.unit(), List.of(), flags);
        }
        flags.addAll(QueryService.flags(metric, jvm));
        Map<Long, double[]> buckets = new LinkedHashMap<>();
        for (RollupRow row : totals(jvm, metric.name(), minute, range)) {
            long bucket = Math.floorDiv(row.ts(), step) * step;
            if (sinceMs != null && bucket <= sinceMs) {
                continue;
            }
            double[] acc = buckets.computeIfAbsent(bucket, ignored -> new double[2]);
            acc[0] += row.sum();
            acc[1] += row.count();
        }
        List<ChartPoint> points = new ArrayList<>();
        for (Map.Entry<Long, double[]> entry : buckets.entrySet()) {
            double count = entry.getValue()[1];
            if (count == 0d) {
                continue;
            }
            points.add(new ChartPoint(entry.getKey(), entry.getValue()[0] / count));
        }
        if (points.size() > SERIES_CAP) {
            points = points.subList(points.size() - SERIES_CAP, points.size());
        }
        return new MetricSeries(metric.name(), metric.unit(), points, flags);
    }

    private List<Marker> markers(JvmRow jvm, List<String> types, V2Range range, Long sinceMs) throws IOException {
        if (types != null && types.isEmpty()) {
            return List.of();
        }
        long fromMs = sinceMs == null ? range.from().toEpochMilli() : sinceMs;
        boolean exclusive = sinceMs != null;
        List<EventRow> rows = store.readEvents(jvm.jvmId(), types, fromMs, range.to().toEpochMilli(), exclusive, MARKER_CAP);
        List<Marker> markers = new ArrayList<>();
        for (EventRow row : rows) {
            if (sinceMs != null && row.ts() <= sinceMs) {
                continue;
            }
            markers.add(new Marker(row.ts(), row.type(), row.durationUs(), row.thread()));
        }
        return markers;
    }

    private List<RollupRow> totals(JvmRow jvm, String metric, boolean minute, V2Range range) throws IOException {
        return totals(jvm, metric, minute, range.from().toEpochMilli(), range.to().toEpochMilli());
    }

    private List<RollupRow> totals(JvmRow jvm, String metric, boolean minute, long fromMs, long toMs) throws IOException {
        return store.readRollup(minute, jvm.jvmId(), metric, fromMs, toMs, "", "");
    }

    private Map<String, MetricSpec> overviewByGroup() {
        Map<String, MetricSpec> found = new LinkedHashMap<>();
        for (MetricSpec metric : catalogue.overviewMetrics()) {
            found.putIfAbsent(metric.group(), metric);
        }
        return found;
    }

    private String availability(ChartSpec chart, JvmRow jvm) {
        if (!chart.onJdk(jvm.jdkMajor())) {
            return "not_on_this_jdk";
        }
        if ("nmt".equals(chart.group()) && jvm.jdkMajor() < 21) {
            return "not_on_this_jdk";
        }
        if ("nmt".equals(chart.group()) && !QueryService.nmtOn(jvm)) {
            return "nmt_off";
        }
        boolean anyOnJdk = false;
        boolean anyPlain = false;
        for (String name : chart.metrics()) {
            MetricSpec metric = catalogue.metric(name);
            if (metric == null || !metric.onJdk(jvm.jdkMajor())) {
                continue;
            }
            anyOnJdk = true;
            if (!metric.optInNmt()) {
                anyPlain = true;
            }
        }
        if (!anyOnJdk) {
            return "not_on_this_jdk";
        }
        if (!anyPlain && !QueryService.nmtOn(jvm)) {
            return "nmt_off";
        }
        return "available";
    }

    private ChartCard card(ChartSpec chart, JvmRow jvm) {
        return new ChartCard(
                chart.id(),
                chart.group(),
                chart.title(),
                availability(chart, jvm),
                chart.views(),
                chart.metrics(),
                chart.events(),
                chart.frames());
    }

    private static Comparator<Lane> laneOrder() {
        return Comparator.comparingInt((Lane lane) -> switch (lane.state()) {
                    case "critical" -> 0;
                    case "warning" -> 1;
                    case "ok" -> 2;
                    case "unavailable" -> 3;
                    default -> 4;
                })
                .thenComparing(
                        lane -> lane.vsNormal() == null ? null : Math.abs(lane.vsNormal()),
                        Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private static double vsNormal(double value, Double normal) {
        if (normal == null || normal == 0d) {
            return Double.NaN;
        }
        return (value - normal) / normal * 100d;
    }

    private static Double vsNormal(Double value, Double normal) {
        if (value == null) {
            return null;
        }
        double vs = vsNormal(value.doubleValue(), normal);
        return Double.isNaN(vs) ? null : vs;
    }

    private static V2Range range(JvmRow jvm, String last, String from, String to) {
        Instant end = Instant.ofEpochMilli(Math.max(jvm.lastMs(), jvm.firstMs()));
        return V2Range.parse(last, from, to, end);
    }

    private static TimeWindow window(V2Range range, String step) {
        return new TimeWindow(V2Times.text(range.from()), V2Times.text(range.to()), step);
    }

    private static JvmRef jvmRef(JvmRow jvm) {
        return new JvmRef(
                jvm.namespace(),
                jvm.podName(),
                jvm.containerName(),
                jvm.clusterId(),
                V2Times.text(jvm.jvmStartMs()),
                jvm.jdkMajor());
    }

    private static Workload workload(JvmRow jvm) {
        return new Workload(
                jvm.clusterId(),
                jvm.namespace(),
                jvm.podName(),
                jvm.containerName(),
                V2Times.text(jvm.jvmStartMs()),
                jvm.jdkMajor(),
                jvm.profile(),
                V2Times.text(jvm.lastMs()),
                jvm.windows());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TimeWindow(String from, String to, String step) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record JvmRef(String namespace, String pod, String container, String cluster, String start, int jdk) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Lane(
            String group,
            String state,
            String metric,
            String stat,
            Double value,
            String unit,
            Double normal,
            Double vsNormal,
            List<String> openIncidents,
            List<String> flags) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Report(TimeWindow window, int jdk, List<Lane> groups) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Workload(
            String cluster,
            String namespace,
            String pod,
            String container,
            String start,
            int jdk,
            String profile,
            String last,
            int windows) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WorkloadList(List<Workload> workloads) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ChartPoint(long t, double v) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MetricSeries(String name, String unit, List<ChartPoint> points, List<String> flags) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Marker(long t, String type, Long durationUs, String thread) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ChartCard(
            String id,
            String group,
            String title,
            String availability,
            List<ViewSpec> views,
            List<String> metrics,
            List<String> events,
            List<String> frames) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ChartList(List<ChartCard> charts) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ChartData(
            String id,
            String title,
            String view,
            String availability,
            TimeWindow window,
            String stepUsed,
            List<MetricSeries> metrics,
            List<Marker> markers) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Segment(String from, String to, String state) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TimelineLane(String group, List<Segment> segments) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TimelineIncident(String id, String type, String group, String level, String t0, String t1) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Timeline(TimeWindow window, List<TimelineLane> lanes, List<TimelineIncident> incidents) {}
}

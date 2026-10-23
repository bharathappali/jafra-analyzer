package io.jafra.analyzer.v2.surface;

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

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.jafra.analyzer.v2.api.V2Exception;
import io.jafra.analyzer.v2.catalogue.ViewSpec;
import io.jafra.analyzer.v2.api.V2Range;
import io.jafra.analyzer.v2.api.V2Times;
import io.jafra.analyzer.v2.catalogue.Catalogue;
import io.jafra.analyzer.v2.frames.FrameService;
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
import io.jafra.analyzer.v2.trend.TrendService;

/**
 * Workloads, overview, timeline, and charts. Chart views are echoed as given: the catalogue does not
 * map metrics onto views, so every view returns every declared metric.
 */
@ApplicationScoped
public class SurfaceService {
    static final int SERIES_CAP = 360;
    static final int MARKER_CAP = 200;
    static final int PROVEN_BASELINE = 60;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final V2Store store;
    private final Catalogue catalogue;
    private final BaselineService baselines;
    private final FrameService frames;
    private final TrendService trends;

    @Inject
    public SurfaceService(
            V2Store store, Catalogue catalogue, BaselineService baselines, FrameService frames, TrendService trends) {
        this.store = store;
        this.catalogue = catalogue;
        this.baselines = baselines;
        this.frames = frames;
        this.trends = trends;
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

    public List<ContainerWorkload> workloads(String cluster) {
        try {
            Map<String, List<JvmRow>> grouped = new LinkedHashMap<>();
            for (JvmRow jvm : store.allJvms()) {
                if (cluster != null && !cluster.isBlank() && !cluster.equals(jvm.clusterId())) {
                    continue;
                }
                String key = jvm.namespace() + "\0" + jvm.podName() + "\0" + jvm.containerName() + "\0" + jvm.clusterId();
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(jvm);
            }
            List<ContainerWorkload> rows = new ArrayList<>();
            for (List<JvmRow> jvms : grouped.values()) {
                jvms.sort(Comparator.comparingLong(JvmRow::jvmStartMs).reversed());
                JvmRow head = jvms.get(0);
                List<JvmCard> cards = new ArrayList<>();
                for (JvmRow jvm : jvms) {
                    cards.add(new JvmCard(
                            V2Times.text(jvm.jvmStartMs()),
                            jvm.jdkMajor() == 0 ? null : jvm.jdkMajor(),
                            jvm.profile(),
                            V2Times.text(jvm.firstMs()),
                            V2Times.text(jvm.lastMs()),
                            jvm.windows()));
                }
                rows.add(new ContainerWorkload(
                        head.clusterId(), head.namespace(), head.podName(), head.containerName(), cards));
            }
            rows.sort(Comparator.comparing(ContainerWorkload::ns)
                    .thenComparing(ContainerWorkload::pod)
                    .thenComparing(ContainerWorkload::container));
            return rows;
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

    public ChartData chart(
            JvmRow jvm, String chartId, String view, String since, String step, String kind, String last, String from, String to) {
        ChartSpec chart = catalogue.chart(chartId);
        if (chart == null) {
            throw V2Exception.unknownChart(catalogue.chartIds());
        }
        V2Range range = range(jvm, last, from, to);
        Long sinceMs = since == null || since.isBlank() ? null : V2Times.parse(since, "since").toEpochMilli();
        long requested = step == null || step.isBlank() ? 5_000 : QueryService.parseStep(step);
        long used = QueryService.coarsen(requested, range.to().toEpochMilli() - range.from().toEpochMilli(), SERIES_CAP);
        boolean minute = used >= 60_000 && used % 60_000 == 0;
        String echoed = view == null || view.isBlank() ? chart.views().get(0).id() : view;
        try {
            List<MetricSeries> series = new ArrayList<>();
            Set<String> flags = new LinkedHashSet<>();
            for (String name : chart.metrics()) {
                MetricSpec metric = catalogue.metric(name);
                if (metric == null) {
                    continue;
                }
                MetricSeries row = series(jvm, metric, range, used, minute, sinceMs);
                series.add(row);
                flags.addAll(row.flags());
            }
            List<ObjectNode> markers = markers(jvm, chart.events(), range, sinceMs);
            Map<String, SeriesSummary> summary = new LinkedHashMap<>();
            for (MetricSeries row : series) {
                SeriesSummary stats = summary(row.points());
                if (stats != null) {
                    summary.put(row.metric(), stats);
                }
            }
            return new ChartData(
                    chart.id(),
                    chart.title(),
                    echoed,
                    availability(chart, jvm),
                    window(range, QueryService.nameOf(used)),
                    QueryService.nameOf(used),
                    List.copyOf(flags),
                    series,
                    markers,
                    flame(jvm, chart, kind, last, from, to),
                    summary,
                    trend(jvm, chart, series, last, from, to));
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
            return new MetricSeries(metric.name(), metric.unit(), "avg", List.of(), flags);
        }
        if (metric.optInNmt() && !QueryService.nmtOn(jvm)) {
            flags.add("nmt_off");
            return new MetricSeries(metric.name(), metric.unit(), "avg", List.of(), flags);
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
        return new MetricSeries(metric.name(), metric.unit(), "avg", points, flags);
    }

    private List<ObjectNode> markers(JvmRow jvm, List<String> types, V2Range range, Long sinceMs) throws IOException {
        if (types != null && types.isEmpty()) {
            return List.of();
        }
        long fromMs = sinceMs == null ? range.from().toEpochMilli() : sinceMs;
        boolean exclusive = sinceMs != null;
        List<EventRow> rows = store.readEvents(jvm.jvmId(), types, fromMs, range.to().toEpochMilli(), exclusive, MARKER_CAP);
        List<ObjectNode> markers = new ArrayList<>();
        for (EventRow row : rows) {
            if (sinceMs != null && row.ts() <= sinceMs) {
                continue;
            }
            ObjectNode marker = JSON.createObjectNode();
            marker.put("t", row.ts());
            marker.put("type", row.type());
            if (row.durationUs() == null) {
                marker.putNull("duration_us");
            } else {
                marker.put("duration_us", row.durationUs());
            }
            marker.put("thread", row.thread());
            marker.put("top_frame", row.topFrame());
            flatten(marker, row.fields());
            markers.add(marker);
        }
        return markers;
    }

    private static void flatten(ObjectNode marker, String fields) {
        if (fields == null || fields.isBlank()) {
            return;
        }
        try {
            JsonNode tree = JSON.readTree(fields);
            if (!tree.isObject()) {
                return;
            }
            tree.fields().forEachRemaining(entry -> {
                if (!marker.has(entry.getKey())) {
                    marker.set(entry.getKey(), entry.getValue());
                }
            });
        } catch (IOException ignored) {
            // a marker keeps the common fields when its catalogue payload is not JSON
        }
    }

    private FrameService.Flame flame(JvmRow jvm, ChartSpec chart, String kind, String last, String from, String to) {
        if (chart.frames() == null || chart.frames().isEmpty()) {
            return null;
        }
        String selected = kind == null || kind.isBlank() ? chart.frames().get(0) : kind;
        try {
            return frames.flame(jvm, chart.group(), selected, null, null, null, last, from, to);
        } catch (V2Exception error) {
            return null;
        }
    }

    private ObjectNode trend(JvmRow jvm, ChartSpec chart, List<MetricSeries> series, String last, String from, String to) {
        String name = trendMetric(chart);
        if (name == null) {
            return null;
        }
        try {
            TrendService.Trend body = trends.trend(jvm, name, last, from, to);
            ObjectNode node = JSON.valueToTree(body);
            ArrayNode line = node.putArray("line");
            List<ChartPoint> points = List.of();
            for (MetricSeries row : series) {
                if (name.equals(row.metric())) {
                    points = row.points();
                    break;
                }
            }
            if (points.size() >= 2) {
                ChartPoint start = points.get(0);
                ChartPoint end = points.get(points.size() - 1);
                double hours = (end.t() - start.t()) / 3_600_000d;
                double y1 = end.v();
                double y0 = y1 - body.slopePerH() * hours;
                line.addArray().add(start.t()).add(y0);
                line.addArray().add(end.t()).add(y1);
            }
            return node;
        } catch (V2Exception error) {
            return null;
        }
    }

    private String trendMetric(ChartSpec chart) {
        for (String name : chart.metrics()) {
            MetricSpec metric = catalogue.metric(name);
            if (metric != null && metric.trend()) {
                return name;
            }
        }
        if (chart.note() != null
                && (chart.note().contains("Theil") || chart.note().toLowerCase().contains("time to limit"))
                && chart.metrics() != null
                && !chart.metrics().isEmpty()) {
            return chart.metrics().get(0);
        }
        return null;
    }

    private static SeriesSummary summary(List<ChartPoint> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        long minAt = points.get(0).t();
        long maxAt = points.get(0).t();
        List<Double> values = new ArrayList<>();
        for (ChartPoint point : points) {
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
        double median = values.size() % 2 == 1
                ? values.get(values.size() / 2)
                : (values.get(values.size() / 2 - 1) + values.get(values.size() / 2)) / 2d;
        ChartPoint first = points.get(0);
        ChartPoint last = points.get(points.size() - 1);
        return new SeriesSummary(first.v(), last.v(), min, minAt, max, maxAt, median);
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
                chart.frames(),
                chart.note());
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
    public record JvmCard(
            String start, Integer jdk, String profile, String first, String last, int windows) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ContainerWorkload(
            String cluster, String ns, String pod, String container, List<JvmCard> jvms) {}

    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChartPoint(long t, double v) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MetricSeries(String metric, String unit, String stat, List<ChartPoint> points, List<String> flags) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SeriesSummary(
            double first, double last, double min, long minAt, double max, long maxAt, double median) {}

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
            List<String> frames,
            String note) {}

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
            List<String> flags,
            List<MetricSeries> series,
            List<ObjectNode> markers,
            FrameService.Flame flame,
            Map<String, SeriesSummary> summary,
            ObjectNode trend) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Segment(String from, String to, String state) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TimelineLane(String group, List<Segment> segments) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TimelineIncident(String id, String type, String group, String level, String t0, String t1) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Timeline(TimeWindow window, List<TimelineLane> lanes, List<TimelineIncident> incidents) {}
}

package io.jafra.analyzer.v2.catalogue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Immutable catalogue loaded once from the frozen metric-catalogue.json. */
@ApplicationScoped
public class Catalogue {
    public static final String RESOURCE = "/jafra/v2/metric-catalogue.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode root;
    private final List<String> groups;
    private final Map<String, MetricSpec> metrics;
    private final Map<String, JsonNode> metricNodes;
    private final Map<String, EventSpec> events;
    private final Map<String, JsonNode> eventNodes;
    private final Map<String, FrameKindSpec> frameKinds;
    private final Map<String, ChartSpec> charts;
    private final List<String> unverified;

    public Catalogue() {
        this(readResource());
    }

    Catalogue(JsonNode root) {
        this.root = root;
        this.groups = texts(root.get("groups"));
        this.metrics = new LinkedHashMap<>();
        this.metricNodes = new LinkedHashMap<>();
        for (JsonNode node : array(root.get("metrics"))) {
            MetricSpec spec = metric(node);
            metrics.put(spec.name(), spec);
            metricNodes.put(spec.name(), node);
        }
        this.events = new LinkedHashMap<>();
        this.eventNodes = new LinkedHashMap<>();
        for (JsonNode node : array(root.get("events"))) {
            EventSpec spec = event(node);
            events.put(spec.type(), spec);
            eventNodes.put(spec.type(), node);
        }
        this.frameKinds = new LinkedHashMap<>();
        for (JsonNode node : array(root.get("frame_kinds"))) {
            FrameKindSpec spec = frameKind(node);
            frameKinds.put(spec.kind(), spec);
        }
        this.charts = new LinkedHashMap<>();
        for (JsonNode node : array(root.get("charts"))) {
            ChartSpec spec = chart(node);
            charts.put(spec.id(), spec);
        }
        this.unverified = new ArrayList<>();
        for (JsonNode node : array(root.get("unverified"))) {
            unverified.add(node.path("item").asText(""));
        }
    }

    public static Catalogue load() {
        return new Catalogue();
    }

    public String version() {
        return root.path("version").asText();
    }

    public String frozen() {
        return root.path("frozen").asText();
    }

    public List<String> groups() {
        return groups;
    }

    public List<MetricSpec> metrics() {
        return List.copyOf(metrics.values());
    }

    public MetricSpec metric(String name) {
        return metrics.get(name);
    }

    public List<EventSpec> events() {
        return List.copyOf(events.values());
    }

    public EventSpec event(String type) {
        return events.get(type);
    }

    public List<FrameKindSpec> frameKinds() {
        return List.copyOf(frameKinds.values());
    }

    public FrameKindSpec frameKind(String kind) {
        return frameKinds.get(kind);
    }

    public List<ChartSpec> charts() {
        return List.copyOf(charts.values());
    }

    public ChartSpec chart(String id) {
        return charts.get(id);
    }

    public List<String> unverified() {
        return unverified;
    }

    public List<MetricSpec> overviewMetrics() {
        List<MetricSpec> found = new ArrayList<>();
        for (MetricSpec spec : metrics.values()) {
            if (spec.overviewKey()) {
                found.add(spec);
            }
        }
        return found;
    }

    public List<MetricSpec> trendMetrics() {
        List<MetricSpec> found = new ArrayList<>();
        for (MetricSpec spec : metrics.values()) {
            if (spec.trend()) {
                found.add(spec);
            }
        }
        return found;
    }

    public List<MetricSpec> histogramMetrics() {
        List<MetricSpec> found = new ArrayList<>();
        for (MetricSpec spec : metrics.values()) {
            if (spec.histogram()) {
                found.add(spec);
            }
        }
        return found;
    }

    /** Catalogue document filtered by optional group and JDK. Keys stay as in the file. */
    public ObjectNode metricsDocument(String group, Integer jdk) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("version", version());
        doc.put("frozen", frozen());
        doc.set("groups", root.get("groups").deepCopy());
        ArrayNode metricsOut = doc.putArray("metrics");
        for (Map.Entry<String, JsonNode> entry : metricNodes.entrySet()) {
            MetricSpec spec = metrics.get(entry.getKey());
            if (group != null && !group.isBlank() && !group.equals(spec.group())) {
                continue;
            }
            if (jdk != null && !spec.onJdk(jdk)) {
                continue;
            }
            metricsOut.add(entry.getValue().deepCopy());
        }
        ArrayNode eventsOut = doc.putArray("events");
        for (JsonNode node : eventNodes.values()) {
            eventsOut.add(node.deepCopy());
        }
        doc.set("frame_kinds", root.get("frame_kinds").deepCopy());
        return doc;
    }

    /**
     * Three closest metric names by Levenshtein distance on the full name. Ties prefer the same
     * group as {@code groupHint} when that hint is itself a catalogue metric.
     */
    public List<String> closestMetrics(String raw, int limit) {
        String query = raw == null ? "" : raw.trim();
        String prefixGroup = null;
        int dot = query.indexOf('.');
        if (dot > 0 && groups.contains(query.substring(0, dot))) {
            prefixGroup = query.substring(0, dot);
        }
        final String hintGroup = prefixGroup;
        List<String> names = new ArrayList<>(metrics.keySet());
        names.sort((left, right) -> {
            int distance = Integer.compare(levenshtein(query, left), levenshtein(query, right));
            if (distance != 0) {
                return distance;
            }
            if (hintGroup != null) {
                boolean leftGroup = hintGroup.equals(metrics.get(left).group());
                boolean rightGroup = hintGroup.equals(metrics.get(right).group());
                if (leftGroup != rightGroup) {
                    return leftGroup ? -1 : 1;
                }
            }
            return left.compareTo(right);
        });
        if (names.size() > limit) {
            return List.copyOf(names.subList(0, limit));
        }
        return List.copyOf(names);
    }

    public List<String> chartIds() {
        return List.copyOf(charts.keySet());
    }

    static int levenshtein(String left, String right) {
        int n = left.length();
        int m = right.length();
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[m];
    }

    private static JsonNode readResource() {
        try (InputStream in = Catalogue.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing catalogue " + RESOURCE);
            }
            return MAPPER.readTree(in);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static MetricSpec metric(JsonNode node) {
        JsonNode source = node.get("source");
        return new MetricSpec(
                node.path("name").asText(),
                node.path("group").asText(),
                node.path("unit").asText(),
                node.path("kind").asText(),
                texts(node.get("stats")),
                texts(node.get("by")),
                node.path("histogram").asBoolean(false),
                textOrNull(node.get("histogram_family")),
                node.path("trend").asBoolean(false),
                textOrNull(node.get("limit")),
                ints(node.get("jdk")),
                source == null ? null : textOrNull(source.get("event")),
                source == null ? null : textOrNull(source.get("field")),
                source == null ? null : textOrNull(source.get("producer")),
                texts(node.get("flags")),
                texts(node.get("charts")),
                node.path("overview_key").asBoolean(false),
                textOrNull(node.get("overview_stat")),
                textOrNull(node.get("derivation")),
                textOrNull(node.get("note")));
    }

    private static EventSpec event(JsonNode node) {
        JsonNode source = node.get("source");
        Map<String, String> fields = new LinkedHashMap<>();
        JsonNode fieldNode = node.get("fields");
        if (fieldNode != null && fieldNode.isObject()) {
            fieldNode.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue().asText()));
        }
        return new EventSpec(
                node.path("type").asText(),
                node.path("group").asText(),
                source == null ? null : textOrNull(source.get("event")),
                Map.copyOf(fields),
                ints(node.get("jdk")),
                texts(node.get("flags")));
    }

    private static FrameKindSpec frameKind(JsonNode node) {
        return new FrameKindSpec(
                node.path("kind").asText(),
                node.path("unit").asText(),
                texts(node.get("flags")),
                texts(node.get("filters")),
                ints(node.get("jdk")));
    }

    private static ChartSpec chart(JsonNode node) {
        List<ViewSpec> views = new ArrayList<>();
        for (JsonNode view : array(node.get("views"))) {
            views.add(new ViewSpec(view.path("id").asText(), view.path("title").asText()));
        }
        return new ChartSpec(
                node.path("id").asText(),
                node.path("group").asText(),
                node.path("title").asText(),
                List.copyOf(views),
                texts(node.get("metrics")),
                texts(node.get("events")),
                texts(node.get("frames")),
                ints(node.get("jdk")),
                textOrNull(node.get("note")));
    }

    private static List<JsonNode> array(JsonNode node) {
        List<JsonNode> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(values::add);
        }
        return values;
    }

    private static List<String> texts(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        }
        return List.copyOf(values);
    }

    private static List<Integer> ints(JsonNode node) {
        List<Integer> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> values.add(item.asInt()));
        }
        return List.copyOf(values);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text.isBlank() ? null : text;
    }
}

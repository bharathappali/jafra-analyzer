package io.jafra.analyzer.v2.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.jafra.analyzer.v2.api.V2Exception;
import io.jafra.analyzer.v2.catalogue.Catalogue;
import io.jafra.analyzer.v2.catalogue.EventSpec;
import io.jafra.analyzer.v2.catalogue.FrameKindSpec;
import io.jafra.analyzer.v2.catalogue.MetricSpec;
import io.jafra.analyzer.v2.frames.EventService;
import io.jafra.analyzer.v2.frames.FrameService;
import io.jafra.analyzer.v2.incidents.IncidentService;
import io.jafra.analyzer.v2.query.QueryService;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;
import io.jafra.analyzer.v2.surface.SurfaceService;
import io.jafra.analyzer.v2.trend.TrendService;

/** Thin wrappers. Query behaviour lives in the services these tools call. */
@ApplicationScoped
public class ToolRegistry {
    public static final List<String> NAMES = List.of(
            "get_overview",
            "list_incidents",
            "get_incident",
            "query_metric",
            "get_trend",
            "list_events",
            "top_frames",
            "get_jvm_info");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SurfaceService surface;
    private final IncidentService incidents;
    private final QueryService queries;
    private final TrendService trends;
    private final EventService events;
    private final FrameService frames;
    private final Catalogue catalogue;

    @Inject
    public ToolRegistry(
            SurfaceService surface,
            IncidentService incidents,
            QueryService queries,
            TrendService trends,
            EventService events,
            FrameService frames,
            Catalogue catalogue) {
        this.surface = surface;
        this.incidents = incidents;
        this.queries = queries;
        this.trends = trends;
        this.events = events;
        this.frames = frames;
        this.catalogue = catalogue;
    }

    public ObjectNode schemas() {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode tools = root.putArray("tools");
        tools.add(tool("get_overview", "18 group lanes for the range", rangeOnly(), List.of()));
        ObjectNode list = rangeOnly();
        list.set("group", enumProp(null, catalogue.groups()));
        list.set("status", enumProp("all", List.of("open", "closed", "all")));
        list.set("level", enumProp(null, List.of("critical", "warning")));
        tools.add(tool("list_incidents", "Incidents overlapping the range, capped at 20", list, List.of()));
        tools.add(tool("get_incident", "One incident", mapOf("id", stringProp("id")), List.of("id")));
        ObjectNode query = rangeOnly();
        query.set("metric", stringProp("catalogue name"));
        query.set("stat", enumProp("avg", List.of("avg", "min", "max", "sum", "count", "rate", "p50", "p95", "p99")));
        query.set("step", stringProp("e.g. 5s, 1m, 5m; coarsened to keep ≤ 60 points"));
        query.set("by", stringProp("by"));
        query.set("where", stringProp("by_name=value"));
        query.set("top", intProp(1, 10, 10));
        tools.add(tool("query_metric", "One metric. At most 10 breakdown rows and 60 points", query, List.of("metric")));
        ObjectNode trend = rangeOnly();
        trend.set("metric", enumProp(null, catalogue.trendMetrics().stream().map(MetricSpec::name).toList()));
        tools.add(tool("get_trend", "Theil-Sen trend. Default last 60m", trend, List.of("metric")));
        ObjectNode eventsTool = rangeOnly();
        eventsTool.set("type", enumProp(null, catalogue.events().stream().map(EventSpec::type).toList()));
        eventsTool.set("sort", enumProp("duration", List.of("duration", "time")));
        eventsTool.set("limit", intProp(1, 20, 20));
        eventsTool.set("where", stringProp("field=value"));
        tools.add(tool("list_events", "One event type, cap 20", eventsTool, List.of("type")));
        ObjectNode framesTool = rangeOnly();
        framesTool.set("kind", enumProp(null, catalogue.frameKinds().stream().map(FrameKindSpec::kind).toList()));
        framesTool.set("thread", stringProp("name prefix"));
        framesTool.set("endpoint", stringProp("handler, JDK 25, cpu|wall"));
        framesTool.set("state", enumProp(null, List.of("running", "waiting")));
        framesTool.set("top", intProp(1, 15, 15));
        framesTool.set("focus", stringProp("frame"));
        framesTool.set("vs", stringProp("<from>/<to>"));
        tools.add(tool("top_frames", "Top frames, cap 15", framesTool, List.of("kind")));
        tools.add(tool("get_jvm_info", "JVM settings recorded from the chunk", JSON.createObjectNode(), List.of()));
        root.set("context", contextSchema());
        return root;
    }

    public Object run(JvmRow jvm, String tool, Map<String, String> query) {
        Map<String, String> args = query == null ? Map.of() : query;
        String from = first(args, "from", "context_from");
        String to = first(args, "to", "context_to");
        String last = args.get("last");
        return switch (tool) {
            case "get_overview" -> surface.report(jvm, last, from, to);
            case "list_incidents" -> incidents.list(
                    jvm, args.get("level"), args.get("status"), args.get("group"), last, from, to);
            case "get_incident" -> {
                String id = args.get("id");
                if (id == null || id.isBlank()) {
                    throw V2Exception.idRequired(incidents.latestIds(jvm));
                }
                yield incidents.get(jvm, id);
            }
            case "query_metric" -> queries.query(
                    jvm, args.get("metric"), args.get("stat"), args.get("by"), args.get("step"), args.get("where"),
                    integer(args.get("top")), last, from, to);
            case "get_trend" -> trends.trend(jvm, args.get("metric"), last == null && from == null ? null : last, from, to);
            case "list_events" -> events.list(
                    jvm, args.get("type"), args.get("sort"), integer(args.get("limit")), args.get("where"), last, from, to);
            case "top_frames" -> frames.top(
                    jvm, args.get("kind"), args.get("thread"), args.get("state"), args.get("endpoint"), args.get("focus"),
                    integer(args.get("top")), last, from, to, args.get("vs"));
            case "get_jvm_info" -> jvmInfo(jvm);
            default -> throw V2Exception.unsupportedBy(tool + " is not a tool", NAMES);
        };
    }

    static ObjectNode jvmInfo(JvmRow jvm) {
        ObjectNode stored;
        try {
            var parsed = JSON.readTree(jvm.settings() == null ? "{}" : jvm.settings());
            stored = parsed instanceof ObjectNode object ? object : JSON.createObjectNode();
        } catch (Exception error) {
            stored = JSON.createObjectNode();
        }
        ObjectNode node = JSON.createObjectNode();
        for (String key : List.of("jvm", "heap", "gc", "jit", "code_cache", "container", "host", "flags", "recording")) {
            JsonNode section = stored.get(key);
            node.set(key, section != null && section.isObject() ? section.deepCopy() : JSON.createObjectNode());
        }
        ObjectNode flags = (ObjectNode) node.get("flags");
        if (!flags.hasNonNull("source")) {
            flags.put("source", "jvmArguments");
        }
        ObjectNode k8s = node.putObject("k8s");
        k8s.put("namespace", jvm.namespace());
        k8s.put("pod", jvm.podName());
        k8s.put("container", jvm.containerName());
        JsonNode heap = node.get("heap");
        if (heap instanceof ObjectNode heapNode) {
            heapNode.remove("xmx");
            heapNode.remove("max_ram_percentage");
        }
        return node;
    }

    private static ObjectNode tool(String name, String description, ObjectNode properties, List<String> required) {
        ObjectNode tool = JSON.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        ObjectNode parameters = tool.putObject("parameters");
        parameters.put("type", "object");
        parameters.set("properties", properties);
        ArrayNode req = parameters.putArray("required");
        required.forEach(req::add);
        parameters.put("additionalProperties", false);
        return tool;
    }

    private static ObjectNode rangeOnly() {
        ObjectNode props = JSON.createObjectNode();
        props.set("from", stringProp("epoch ms or RFC 3339 UTC"));
        props.set("to", stringProp("epoch ms or RFC 3339 UTC"));
        props.set("last", stringProp("90s, 5m, 15m, 1h, 1d …, max 7d; not with from/to; default: context range"));
        return props;
    }

    private static ObjectNode mapOf(String name, ObjectNode value) {
        ObjectNode props = JSON.createObjectNode();
        props.set(name, value);
        return props;
    }

    private static ObjectNode stringProp(String description) {
        return JSON.createObjectNode().put("type", "string").put("description", description);
    }

    private static ObjectNode intProp(int minimum, int maximum, int fallback) {
        return JSON.createObjectNode()
                .put("type", "integer")
                .put("minimum", minimum)
                .put("maximum", maximum)
                .put("default", fallback);
    }

    private static ObjectNode enumProp(String fallback, List<String> values) {
        ObjectNode node = JSON.createObjectNode().put("type", "string");
        if (fallback != null) {
            node.put("default", fallback);
        }
        ArrayNode enums = node.putArray("enum");
        values.forEach(enums::add);
        return node;
    }

    private static ObjectNode contextSchema() {
        ObjectNode context = JSON.createObjectNode();
        context.put("type", "object");
        context.put("additionalProperties", false);
        ObjectNode props = context.putObject("properties");
        props.set("jvm", objectSchema(false, "ns", "pod", "container", "start", "jdk"));
        props.set("range", objectSchema(false, "from", "to"));
        ObjectNode chart = objectSchema(false, "id", "title", "view");
        chart.putArray("type").add("object").add("null");
        chart.with("properties").set("metrics", arrayOfString());
        chart.with("properties").set("events", arrayOfString());
        chart.with("properties").set("frames", arrayOfString());
        props.set("chart", chart);
        ObjectNode selection = JSON.createObjectNode();
        selection.putArray("type").add("object").add("null");
        selection.put("additionalProperties", true);
        ObjectNode selectionProps = selection.putObject("properties");
        selectionProps.set("kind", enumProp(null, List.of("point", "range", "bar", "cell", "frame", "incident", "chart")));
        props.set("selection", selection);
        props.set("summary", JSON.createObjectNode().put("type", "string"));
        ObjectNode open = JSON.createObjectNode().put("type", "array");
        open.set("items", objectSchema(false, "id", "headline"));
        props.set("open_incidents", open);
        return context;
    }

    private static ObjectNode objectSchema(boolean extra, String... names) {
        ObjectNode node = JSON.createObjectNode().put("type", "object").put("additionalProperties", extra);
        ObjectNode props = node.putObject("properties");
        for (String name : names) {
            String type = "jdk".equals(name) ? "integer" : "string";
            props.set(name, JSON.createObjectNode().put("type", type));
        }
        return node;
    }

    private static ObjectNode arrayOfString() {
        ObjectNode node = JSON.createObjectNode().put("type", "array");
        node.putObject("items").put("type", "string");
        return node;
    }

    private static String first(Map<String, String> args, String name, String fallback) {
        String value = args.get(name);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return args.get(fallback);
    }

    private static Integer integer(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Integer.valueOf(raw);
    }

    public static Map<String, String> copy(Map<String, List<String>> query) {
        Map<String, String> flat = new LinkedHashMap<>();
        if (query == null) {
            return flat;
        }
        query.forEach((key, values) -> {
            if (values != null && !values.isEmpty()) {
                flat.put(key, values.get(0));
            }
        });
        return flat;
    }
}

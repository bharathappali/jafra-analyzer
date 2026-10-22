package io.jafra.analyzer.v2.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.jafra.analyzer.v2.api.V2Exception;
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

    @Inject
    public ToolRegistry(
            SurfaceService surface,
            IncidentService incidents,
            QueryService queries,
            TrendService trends,
            EventService events,
            FrameService frames) {
        this.surface = surface;
        this.incidents = incidents;
        this.queries = queries;
        this.trends = trends;
        this.events = events;
        this.frames = frames;
    }

    public ObjectNode schemas() {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode tools = root.putArray("tools");
        tools.add(schema("get_overview", "18 group lanes for the range", Map.of(), List.of()));
        tools.add(schema(
                "list_incidents",
                "Incidents overlapping the JVM, capped at 20",
                Map.of("level", enumProp("level", List.of("warning", "critical")), "status", enumProp("status", List.of("open", "closed")), "group", stringProp("group")),
                List.of()));
        tools.add(schema("get_incident", "One incident", Map.of("id", stringProp("id")), List.of("id")));
        tools.add(schema(
                "query_metric",
                "One metric. At most 10 breakdown rows and 60 points",
                Map.of(
                        "metric", stringProp("metric"),
                        "stat", stringProp("stat, default avg"),
                        "by", stringProp("by"),
                        "step", stringProp("step"),
                        "where", stringProp("where"),
                        "top", intProp("top, default 10, cap 10")),
                List.of("metric")));
        tools.add(schema(
                "get_trend",
                "Theil-Sen trend for one of the 9 gauges. Default last 60m, at most 360 points",
                Map.of("metric", stringProp("metric")),
                List.of("metric")));
        tools.add(schema(
                "list_events",
                "One event type, cap 20",
                Map.of(
                        "type", stringProp("type"),
                        "sort", enumProp("sort, default time", List.of("time", "duration")),
                        "limit", intProp("limit, default 20, cap 20"),
                        "where", stringProp("where")),
                List.of("type")));
        tools.add(schema(
                "top_frames",
                "Top frames, cap 15. Focus is at most 5 paths of 8 frames",
                Map.of(
                        "kind", enumProp("kind", List.of("cpu", "alloc", "lock", "wall", "socket")),
                        "thread", stringProp("thread"),
                        "state", stringProp("state, wall only"),
                        "endpoint", stringProp("endpoint"),
                        "focus", stringProp("focus")),
                List.of("kind")));
        tools.add(schema("get_jvm_info", "JVM settings recorded from the chunk", Map.of(), List.of()));
        ObjectNode context = root.putObject("context");
        context.put("type", "object");
        ObjectNode props = context.putObject("properties");
        props.set("range", JSON.createObjectNode().put("type", "object"));
        props.set("jvm", JSON.createObjectNode().put("type", "object"));
        props.set("chart", JSON.createObjectNode().put("type", "object"));
        props.set("selection", JSON.createObjectNode().put("type", "object"));
        return root;
    }

    public Object run(JvmRow jvm, String tool, Map<String, String> query) {
        Map<String, String> args = query == null ? Map.of() : query;
        String from = first(args, "from", "context_from");
        String to = first(args, "to", "context_to");
        String last = args.get("last");
        return switch (tool) {
            case "get_overview" -> surface.report(jvm, last, from, to);
            case "list_incidents" -> incidents.list(jvm, args.get("level"), args.get("status"), args.get("group"));
            case "get_incident" -> {
                String id = args.get("id");
                if (id == null || id.isBlank()) {
                    throw V2Exception.badRange("id is required");
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
                    last, from, to, args.get("vs_from"), args.get("vs_to"));
            case "get_jvm_info" -> jvmInfo(jvm);
            default -> throw V2Exception.unsupportedBy(tool + " is not a tool", NAMES);
        };
    }

    private static ObjectNode jvmInfo(JvmRow jvm) {
        ObjectNode node;
        try {
            var parsed = JSON.readTree(jvm.settings() == null ? "{}" : jvm.settings());
            node = parsed instanceof ObjectNode object ? object : JSON.createObjectNode();
        } catch (Exception error) {
            node = JSON.createObjectNode();
        }
        node.put("namespace", jvm.namespace());
        node.put("pod", jvm.podName());
        node.put("container", jvm.containerName());
        node.put("jdk", jvm.jdkMajor());
        node.put("profile", jvm.profile());
        return node;
    }

    private static ObjectNode schema(String name, String description, Map<String, ObjectNode> properties, List<String> required) {
        ObjectNode tool = JSON.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        ObjectNode parameters = tool.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode props = parameters.putObject("properties");
        properties.forEach(props::set);
        ArrayNode req = parameters.putArray("required");
        required.forEach(req::add);
        return tool;
    }

    private static ObjectNode stringProp(String description) {
        return JSON.createObjectNode().put("type", "string").put("description", description);
    }

    private static ObjectNode intProp(String description) {
        return JSON.createObjectNode().put("type", "integer").put("description", description);
    }

    private static ObjectNode enumProp(String description, List<String> values) {
        ObjectNode node = stringProp(description);
        ArrayNode enums = node.putArray("enum");
        values.forEach(enums::add);
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

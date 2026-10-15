package io.jafra.analyzer.mcp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.jafra.analyzer.api.v2.V2ReadService;
import io.jafra.analyzer.api.v2.V2Window;

/** Thin MCP wrappers over /v2. Compact format, clamped limits, and a byte budget. */
public final class McpTools {
    static final int SERIES_POINTS = 120;
    static final int TOP_N = 15;
    static final int EVENT_LIMIT = 20;
    static final int STACK_DEPTH = 16;

    private final V2ReadService reads;

    public McpTools(V2ReadService reads) {
        this.reads = reads;
    }

    public Map<String, Object> call(String name, Map<String, Object> arguments) throws Exception {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        if ("list_workloads".equals(name)) {
            return reads.workloads(Instant.now(), Boolean.TRUE.equals(args.get("include_tombstoned")));
        }
        V2ReadService.Identity id = identity(args);
        V2Window.Range range = window(args);
        return switch (name) {
            case "get_instance_info" -> reads.instance(id);
            case "health_overview" -> reads.overview(id, range, true);
            case "list_markers" -> reads.markers(id, range, text(args.get("kinds")), text(args.get("min_severity")));
            case "list_findings" -> reads.findings(id, range);
            case "get_series" -> reads.series(id, range, text(args.get("step")), metrics(args.get("metrics")), true, SERIES_POINTS);
            case "top" -> reads.top(id, range, required(args, "kind"), textOr(args.get("group"), "method"), clamp(number(args.get("n"), 10), 1, TOP_N),
                    null, true);
            case "list_events" -> reads.events(id, range, required(args, "type"), decimal(args.get("min_ms")), text(args.get("cursor")),
                    clamp(number(args.get("limit"), EVENT_LIMIT), 1, EVENT_LIMIT));
            case "compare_windows" -> reads.compare(id, range, compare(args), metrics(args.get("metrics")), true);
            case "get_stack" -> stack(id, required(args, "stack_id"), clamp(number(args.get("depth"), 12), 1, STACK_DEPTH));
            case "heap_summary" -> reads.heapSummary(id, range);
            case "get_correlation" -> reads.correlate(id, range, required(args, "a"), required(args, "b"));
            case "gc_pause_stats" -> fold(id, range, "gc.pause_ms.sum,gc.pause_ms.max,gc.pause_ms.n");
            case "safepoint_stats" -> fold(id, range, "safepoint.ms.sum,safepoint.ms.n");
            case "vm_ops_by_type" -> grouped(id, range, "vm_op");
            case "contention_by_monitor" -> reads.top(id, range, "lock", "monitor", TOP_N, null, true);
            case "thread_state_breakdown" -> quiet(id);
            case "io_top_targets" -> reads.top(id, range, "io", "target", TOP_N, null, true);
            case "nmt_summary" -> nmt(id);
            case "rss_breakdown" -> fold(id, range, "rss.last");
            case "jit_stats" -> fold(id, range, "compile.ms.sum,compile.ms.n");
            case "deoptimizations_by_reason" -> grouped(id, range, "deopt");
            case "throttling_stats" -> throttle(id, range);
            default -> throw new IllegalArgumentException("unknown tool " + name);
        };
    }

    public static Map<String, Object> fit(Map<String, Object> body, int budget, ObjectMapper json) throws Exception {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        while (json.writeValueAsBytes(copy).length > budget) {
            if (!dropHalf(copy)) {
                copy.put("status", "truncated");
                break;
            }
            copy.put("truncated", true);
        }
        return copy;
    }

    private Map<String, Object> stack(V2ReadService.Identity id, String stackId, int depth) throws Exception {
        Map<String, Object> body = reads.stack(id, stackId, depth);
        if (body == null) {
            Map<String, Object> missing = identity(id);
            missing.put("status", "no_data_in_window");
            missing.put("stack_id", stackId);
            return missing;
        }
        body.put("status", "ok");
        return body;
    }

    private Map<String, Object> fold(V2ReadService.Identity id, V2Window.Range range, String metrics) throws Exception {
        Map<String, Object> series = reads.series(id, range, "auto", metrics, true, SERIES_POINTS);
        Map<String, Object> response = identity(id);
        @SuppressWarnings("unchecked")
        Map<String, Object> blocks = (Map<String, Object>) series.get("series");
        boolean any = false;
        if (blocks != null) {
            for (Map.Entry<String, Object> entry : blocks.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> block = (Map<String, Object>) entry.getValue();
                if ("unavailable".equals(block.get("status"))) {
                    response.put("status", "unavailable");
                    copy(response, block, "reason");
                    copy(response, block, "hint");
                    copy(response, block, "min_jdk");
                    return response;
                }
                Object value = reduce(entry.getKey(), block.get("v"));
                if (value != null) {
                    any = true;
                    response.put(entry.getKey(), value);
                }
            }
        }
        response.put("status", any ? "ok" : "no_data_in_window");
        return response;
    }

    private Map<String, Object> grouped(V2ReadService.Identity id, V2Window.Range range, String type) throws Exception {
        Map<String, Object> events = reads.events(id, range, type, null, null, EVENT_LIMIT);
        Map<String, Object> response = identity(id);
        response.put("status", events.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) events.get("rows");
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                Object key = row.get("target") != null ? row.get("target") : row.get("type");
                counts.merge(String.valueOf(key), 1, Integer::sum);
            }
        }
        if (!counts.isEmpty()) {
            response.put("counts", counts);
        }
        if (events.get("next_cursor") != null) {
            response.put("truncated", true);
            response.put("next_cursor", events.get("next_cursor"));
        }
        return response;
    }

    private Map<String, Object> throttle(V2ReadService.Identity id, V2Window.Range range) throws Exception {
        Map<String, Object> folded = fold(id, range, "container.throttled_slices.sum,container.elapsed_slices.sum");
        if (!"ok".equals(folded.get("status"))) {
            return folded;
        }
        double throttled = number(folded.get("container.throttled_slices.sum"));
        double elapsed = number(folded.get("container.elapsed_slices.sum"));
        if (elapsed > 0.0) {
            folded.put("throttle_ratio", Math.round(throttled / elapsed * 1000.0) / 1000.0);
        }
        return folded;
    }

    private Map<String, Object> nmt(V2ReadService.Identity id) throws Exception {
        Map<String, Object> instance = reads.instance(id);
        Map<String, Object> response = identity(id);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> signals = (List<Map<String, Object>>) instance.get("signals");
        if (signals != null) {
            for (Map<String, Object> signal : signals) {
                if ("nmt".equals(signal.get("id"))) {
                    response.put("status", signal.get("status"));
                    copy(response, signal, "reason");
                    copy(response, signal, "hint");
                    copy(response, signal, "min_jdk");
                    return response;
                }
            }
        }
        response.put("status", "no_data_in_window");
        return response;
    }

    private static Map<String, Object> quiet(V2ReadService.Identity id) {
        Map<String, Object> response = identity(id);
        response.put("status", "no_data_in_window");
        return response;
    }

    private static Object reduce(String name, Object values) {
        if (!(values instanceof List<?> points) || points.isEmpty()) {
            return null;
        }
        boolean max = name.endsWith(".max") || name.endsWith(".last");
        double acc = max ? Double.NEGATIVE_INFINITY : 0.0;
        boolean seen = false;
        for (Object point : points) {
            if (!(point instanceof Number number)) {
                continue;
            }
            seen = true;
            acc = max ? Math.max(acc, number.doubleValue()) : acc + number.doubleValue();
        }
        return seen ? acc : null;
    }

    private V2ReadService.Identity identity(Map<String, Object> args) throws Exception {
        Map<String, Object> workload = map(args.get("workload"));
        String ns = required(workload, "ns");
        String pod = required(workload, "pod");
        String container = required(workload, "container");
        String instance = required(workload, "jvm_instance_id");
        if ("latest".equals(instance)) {
            instance = reads.latestInstance(ns, pod, container);
            if (instance == null) {
                throw new IllegalArgumentException("no JVM instance for " + ns + "/" + pod + "/" + container);
            }
        }
        return new V2ReadService.Identity(ns, pod, container, instance);
    }

    private static V2Window.Range window(Map<String, Object> args) {
        Map<String, Object> window = map(args.get("window"));
        return V2Window.parse(Instant.now(), text(window.get("last")), text(window.get("from")), text(window.get("to")));
    }

    private static V2Window.Range compare(Map<String, Object> args) {
        String from = required(args, "compare_from");
        String to = required(args, "compare_to");
        return V2Window.parse(Instant.now(), null, from, to);
    }

    private static String metrics(Object value) {
        if (value instanceof List<?> list) {
            return String.join(",", list.stream().map(String::valueOf).toList());
        }
        String text = text(value);
        if (text == null) {
            throw new IllegalArgumentException("metrics is required");
        }
        return text;
    }

    private static String required(Map<String, Object> args, String key) {
        String value = text(args.get(key));
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static Map<String, Object> identity(V2ReadService.Identity id) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("identity", Map.of(
                "ns", id.ns(), "pod", id.pod(), "container", id.container(), "jvm_instance_id", id.instance()));
        return response;
    }

    private static void copy(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean dropHalf(Map<String, Object> body) {
        String longest = null;
        int size = 0;
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            if (entry.getValue() instanceof List<?> list && list.size() > size) {
                longest = entry.getKey();
                size = list.size();
            }
        }
        if (longest == null || size < 2) {
            return false;
        }
        List<Object> list = new ArrayList<>((List<Object>) body.get(longest));
        body.put(longest, new ArrayList<>(list.subList(0, list.size() / 2)));
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, item) -> out.put(String.valueOf(key), item));
        return out;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String textOr(Object value, String fallback) {
        String text = text(value);
        return text == null ? fallback : text;
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static Double decimal(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}

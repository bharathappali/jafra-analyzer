package io.jafra.analyzer.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP tool names, input schemas, and JSON size budgets. */
public final class McpCatalog {
    public static final int OVERVIEW_BYTES = 2_048;
    public static final int TOP_BYTES = 2_048;
    public static final int SERIES_BYTES = 8_192;
    public static final int STACK_BYTES = 4_096;

    private McpCatalog() {}

    public static List<Tool> tools() {
        List<Tool> tools = new ArrayList<>();
        tools.add(new Tool("list_workloads", "Workload tree. No instance is required.", SERIES_BYTES, false, workloadsSchema()));
        tools.add(tool("get_instance_info", "JVM facts and signal verdicts.", SERIES_BYTES));
        tools.add(tool("health_overview", "One compact block per subsystem.", OVERVIEW_BYTES));
        tools.add(tool("list_markers", "Timeline markers in the window.", SERIES_BYTES, Map.of("kinds", stringProp(), "min_severity", stringProp()), List.of()));
        tools.add(tool("list_findings", "Detector codes for the window.", SERIES_BYTES));
        tools.add(tool("get_series", "At most 4 metrics and 120 points.", SERIES_BYTES, Map.of("metrics", stringProp(), "step", stringProp()), List.of("metrics")));
        tools.add(tool("top", "Top keys. n is at most 15.", TOP_BYTES,
                Map.of("kind", stringProp(), "group", stringProp(), "n", Map.of("type", "integer")), List.of("kind")));
        tools.add(tool("list_events", "Events. limit is at most 20.", SERIES_BYTES,
                Map.of("type", stringProp(), "cursor", stringProp(), "limit", Map.of("type", "integer"), "min_ms", Map.of("type", "number")),
                List.of("type")));
        tools.add(tool("compare_windows", "Current window against compare_from/compare_to.", OVERVIEW_BYTES,
                Map.of("metrics", stringProp(), "compare_from", stringProp(), "compare_to", stringProp()),
                List.of("metrics", "compare_from", "compare_to")));
        tools.add(tool("get_stack", "Frames for a stack id from top. depth is at most 16.", STACK_BYTES,
                Map.of("stack_id", stringProp(), "depth", Map.of("type", "integer")), List.of("stack_id")));
        tools.add(tool("heap_summary", "GC counts, after-GC floor trend, and promotion rate.", OVERVIEW_BYTES));
        tools.add(tool("get_correlation", "Lagged Pearson r for two metrics.", OVERVIEW_BYTES,
                Map.of("a", stringProp(), "b", stringProp()), List.of("a", "b")));
        tools.add(tool("gc_pause_stats", "Pause sum, max, and count.", OVERVIEW_BYTES));
        tools.add(tool("safepoint_stats", "Safepoint time and count.", OVERVIEW_BYTES));
        tools.add(tool("vm_ops_by_type", "VM operation rows grouped by type.", OVERVIEW_BYTES));
        tools.add(tool("contention_by_monitor", "Lock time by monitor class.", TOP_BYTES));
        tools.add(tool("thread_state_breakdown", "Thread-state shares. Empty until those samples are stored.", OVERVIEW_BYTES));
        tools.add(tool("io_top_targets", "I/O time by target.", TOP_BYTES));
        tools.add(tool("nmt_summary", "Native memory tracking verdict.", OVERVIEW_BYTES));
        tools.add(tool("rss_breakdown", "Resident set size in the window.", OVERVIEW_BYTES));
        tools.add(tool("jit_stats", "Compilation time and count.", OVERVIEW_BYTES));
        tools.add(tool("deoptimizations_by_reason", "Deoptimization rows grouped by reason.", OVERVIEW_BYTES));
        tools.add(tool("throttling_stats", "Throttled slices over elapsed slices.", OVERVIEW_BYTES));
        return List.copyOf(tools);
    }

    public static List<Map<String, Object>> listed() {
        List<Map<String, Object>> listed = new ArrayList<>();
        for (Tool tool : tools()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", tool.name());
            row.put("description", tool.description());
            row.put("inputSchema", tool.schema());
            listed.add(row);
        }
        return listed;
    }

    public static int budget(String name) {
        for (Tool tool : tools()) {
            if (tool.name().equals(name)) {
                return tool.budget();
            }
        }
        return SERIES_BYTES;
    }

    private static Tool tool(String name, String description, int budget) {
        return tool(name, description, budget, Map.of(), List.of());
    }

    private static Tool tool(String name, String description, int budget, Map<String, Object> extra, List<String> required) {
        return new Tool(name, description, budget, true, workloadSchema(extra, required));
    }

    private static Map<String, Object> workloadsSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("include_tombstoned", Map.of("type", "boolean")));
        schema.put("required", List.of());
        return schema;
    }

    private static Map<String, Object> workloadSchema(Map<String, Object> extra, List<String> extraRequired) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("workload", Map.of(
                "type", "object",
                "required", List.of("ns", "pod", "container", "jvm_instance_id"),
                "properties", Map.of(
                        "ns", stringProp(), "pod", stringProp(), "container", stringProp(), "jvm_instance_id", stringProp())));
        properties.put("window", Map.of(
                "type", "object",
                "properties", Map.of("last", stringProp(), "from", stringProp(), "to", stringProp())));
        properties.putAll(extra);
        List<String> required = new ArrayList<>();
        required.add("workload");
        required.addAll(extraRequired);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> stringProp() {
        return Map.of("type", "string");
    }

    public record Tool(String name, String description, int budget, boolean workload, Map<String, Object> schema) {}
}

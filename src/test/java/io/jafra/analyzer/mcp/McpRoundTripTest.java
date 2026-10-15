package io.jafra.analyzer.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.jafra.analyzer.api.v2.V2ReadService;
import io.jafra.analyzer.ingest.decode.IngestCatalog;
import io.jafra.analyzer.ingest.decode.ParquetTables;

class McpRoundTripTest {
    @Test
    void getStackRoundTripsAnIdFromTopAndStaysInsideTheBudget() throws Exception {
        Path root = Files.createTempDirectory("jafra-mcp");
        long t = Instant.parse("2026-09-12T12:00:00Z").getEpochSecond() * 1_000_000_000L;
        try (IngestCatalog catalog = new IngestCatalog(root.resolve("v2"))) {
            writeStacks(catalog, root, t);
            V2ReadService reads = new V2ReadService(catalog.connection(), root.resolve("v2"));
            McpTools tools = new McpTools(reads);
            Map<String, Object> top = tools.call("top", args("cpu", 100));
            assertEquals("ok", top.get("status"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) top.get("rows");
            assertTrue(rows.size() <= McpTools.TOP_N);
            String stackId = String.valueOf(rows.get(0).get("stack"));
            Map<String, Object> stack = tools.call("get_stack", workload(Map.of("stack_id", stackId, "depth", 40)));
            assertEquals("ok", stack.get("status"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> frames = (List<Map<String, Object>>) stack.get("frames");
            assertEquals("app.Hot.burn", frames.get(0).get("label"));
            assertTrue(frames.size() <= McpTools.STACK_DEPTH);
            byte[] json = new ObjectMapper().writeValueAsBytes(stack);
            assertTrue(json.length <= McpCatalog.STACK_BYTES);
        }
    }

    @Test
    void correlateReportsLagAndAnOversizedListIsCutToTheBudget() throws Exception {
        Path root = Files.createTempDirectory("jafra-mcp-corr");
        Instant start = Instant.parse("2026-09-12T12:00:00Z");
        long origin = start.getEpochSecond() * 1_000_000_000L;
        try (IngestCatalog catalog = new IngestCatalog(root.resolve("v2"))) {
            List<List<Object>> rows = new ArrayList<>();
            for (int minute = 0; minute < 25; minute++) {
                long bucket = origin + minute * 60_000_000_000L;
                rows.add(rollup(bucket, "alloc.bytes", minute));
                rows.add(rollup(bucket, "gc.pause_ms", minute * 2.0));
            }
            Path file = ParquetTables.partitionFile(root.resolve("v2"), "rollup_1m", "default", "pod", "app", "abc", origin, "roll");
            ParquetTables.write(catalog.connection(), file, "rollup_1m", List.of(
                    "namespace", "pod", "container", "jvm_instance_id", "t", "metric", "bucket_t", "n", "sum", "min", "max", "p50", "p90", "p99", "last"),
                    rows);
            V2ReadService reads = new V2ReadService(catalog.connection(), root.resolve("v2"));
            Map<String, Object> body = new McpTools(reads).call("get_correlation", workload(Map.of(
                    "a", "alloc.bytes",
                    "b", "gc.pause_ms",
                    "window", Map.of("from", "2026-09-12T12:00:00Z", "to", "2026-09-12T12:25:00Z"))));
            assertEquals("ok", body.get("status"));
            assertEquals(0, ((Number) body.get("lag")).intValue());
            assertTrue(((Number) body.get("n")).intValue() >= 20);
        }
        Map<String, Object> huge = new java.util.LinkedHashMap<>();
        huge.put("status", "ok");
        huge.put("rows", new ArrayList<>(java.util.Collections.nCopies(400, Map.of("key", "x".repeat(80)))));
        Map<String, Object> fitted = McpTools.fit(huge, McpCatalog.TOP_BYTES, new ObjectMapper());
        assertTrue(new ObjectMapper().writeValueAsBytes(fitted).length <= McpCatalog.TOP_BYTES);
        assertEquals(true, fitted.get("truncated"));
    }

    private static void writeStacks(IngestCatalog catalog, Path root, long t) throws Exception {
        List<List<Object>> top = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            top.add(row("default", "pod", "app", "abc", t, "cpu", "method", "burn" + i, i == 0 ? "S1" : "S" + i, i == 0 ? 500.0 : 10.0, 20L));
        }
        Path topFile = ParquetTables.partitionFile(root.resolve("v2"), "top_1m", "default", "pod", "app", "abc", t, "top");
        ParquetTables.write(catalog.connection(), topFile, "top_1m",
                List.of("namespace", "pod", "container", "jvm_instance_id", "t", "kind", "grp", "key", "stack_id", "weight", "n"), top);
        Path stack = ParquetTables.partitionFile(root.resolve("v2"), "stack", "default", "pod", "app", "abc", t, "stack");
        ParquetTables.write(catalog.connection(), stack, "stack",
                List.of("namespace", "pod", "container", "jvm_instance_id", "t", "stack_id", "frames", "leaf_frame_id", "top_app_frame_id", "depth"),
                List.of(row("default", "pod", "app", "abc", t, "S1", "F1", "F1", "F1", 1)));
        Path frame = ParquetTables.partitionFile(root.resolve("v2"), "frame", "default", "pod", "app", "abc", t, "frame");
        ParquetTables.write(catalog.connection(), frame, "frame",
                List.of("namespace", "pod", "container", "jvm_instance_id", "t", "frame_id", "class", "method", "descriptor", "line", "type", "category"),
                List.of(row("default", "pod", "app", "abc", t, "F1", "app.Hot", "burn", "()V", 12, "java", "app")));
    }

    private static Map<String, Object> args(String kind, int n) {
        return workload(Map.of("kind", kind, "n", n, "window", Map.of("from", "2026-09-12T12:00:00Z", "to", "2026-09-12T12:01:00Z")));
    }

    private static Map<String, Object> workload(Map<String, Object> extra) {
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("workload", Map.of("ns", "default", "pod", "pod", "container", "app", "jvm_instance_id", "abc"));
        args.putAll(extra);
        return args;
    }

    private static List<Object> rollup(long bucket, String metric, double value) {
        return row("default", "pod", "app", "abc", bucket, metric, bucket, 1L, value, value, value, value, value, value, value);
    }

    private static List<Object> row(Object... values) {
        List<Object> row = new ArrayList<>();
        for (Object value : values) {
            row.add(value);
        }
        return row;
    }
}

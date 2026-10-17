package io.jafra.analyzer.api.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.jafra.analyzer.ingest.decode.IngestCatalog;
import io.jafra.analyzer.ingest.decode.ParquetTables;

class V2ReadServiceTest {
    private static final Instant START = Instant.parse("2026-09-12T12:00:00Z");
    private static final long T0 = START.getEpochSecond() * 1_000_000_000L;

    @Test
    void seriesKeepsGapsAndFitsTheSizeBudget() throws Exception {
        Path root = Files.createTempDirectory("jafra-v2");
        try (IngestCatalog catalog = new IngestCatalog(root.resolve("v2"))) {
            writeSeries(catalog, root.resolve("v2"));
            writeTop(catalog, root.resolve("v2"), 10L);
            V2ReadService service = new V2ReadService(catalog.connection(), root.resolve("v2"));
            V2ReadService.Identity id = new V2ReadService.Identity("default", "pod", "app", "abc");
            V2Window.Range threeSeconds = new V2Window.Range(START, START.plusSeconds(3));
            Map<String, Object> series = service.series(id, threeSeconds, "1s", "cpu.jvm_user", true);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) ((Map<?, ?>) series.get("series")).get("cpu.jvm_user");
            @SuppressWarnings("unchecked")
            List<Object> values = (List<Object>) body.get("v");
            assertEquals(3, values.size());
            assertNull(values.get(1));
            assertEquals("ok", body.get("status"));

            V2Window.Range fourMinutes = new V2Window.Range(START, START.plusSeconds(240));
            Map<String, Object> wide = service.series(id, fourMinutes, "auto", "cpu.jvm_user,gc.pause_ms.p99,threads.live,compile.ms", true);
            assertEquals("1s", wide.get("step"));
            byte[] encoded = new ObjectMapper().writeValueAsBytes(wide);
            assertTrue(encoded.length <= 8192, "series bytes " + encoded.length);
            Map<String, Object> overview = service.overview(id, fourMinutes, true);
            assertTrue(new ObjectMapper().writeValueAsBytes(overview).length <= 2048);
            assertEquals("no_data_in_window", ((Map<?, ?>) ((Map<?, ?>) overview.get("blocks")).get("heap")).get("status"));

            Map<String, Object> top = service.top(id, fourMinutes, "cpu", "method", 10, null, true);
            assertEquals("insufficient_samples", top.get("status"));
            assertTrue(new ObjectMapper().writeValueAsBytes(top).length <= 2048);

            writeSignal(catalog, root.resolve("v2"));
            Map<String, Object> rss = service.series(id, threeSeconds, "1s", "rss", true);
            @SuppressWarnings("unchecked")
            Map<String, Object> rssBody = (Map<String, Object>) ((Map<?, ?>) rss.get("series")).get("rss");
            assertEquals("unavailable", rssBody.get("status"));
            assertEquals("jdk_version", rssBody.get("reason"));
            assertEquals(21, ((Number) rssBody.get("min_jdk")).intValue());
            assertNull(rssBody.get("v"));

            Map<String, Object> zeros = service.series(id, threeSeconds, "1s", "classes.unloaded", true);
            @SuppressWarnings("unchecked")
            Map<String, Object> unloaded = (Map<String, Object>) ((Map<?, ?>) zeros.get("series")).get("classes.unloaded");
            assertEquals("ok", unloaded.get("status"));
            assertEquals(0, ((Number) ((List<?>) unloaded.get("v")).get(0)).intValue());
        }
    }

    private static void writeSignal(IngestCatalog catalog, Path v2) throws Exception {
        Path file = ParquetTables.partitionFile(v2, "signal", "default", "pod", "app", "abc", T0, "signals");
        ParquetTables.write(catalog.connection(), file, "signal",
                List.of("namespace", "pod", "container", "jvm_instance_id", "t", "signal_id", "status", "reason", "min_jdk", "hint"),
                List.of(List.of("default", "pod", "app", "abc", T0, "rss", "unavailable", "jdk_version", 21,
                        "Upgrade to JDK 21+ to see this (jdk.ResidentSetSize).")));
        Path zeros = ParquetTables.partitionFile(v2, "rollup_1s", "default", "pod", "app", "abc", T0, "bucket-zero");
        ParquetTables.write(catalog.connection(), zeros, "rollup_1s",
                List.of("namespace", "pod", "container", "jvm_instance_id", "t", "metric", "bucket_t", "n", "sum", "min", "max", "p50",
                        "p90", "p99", "last"),
                List.of(List.of("default", "pod", "app", "abc", T0, "classes.unloaded", T0, 1L, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)));
    }

    @Test
    void eventsPageOnTimeAndSequence() throws Exception {
        Path root = Files.createTempDirectory("jafra-v2-events");
        try (IngestCatalog catalog = new IngestCatalog(root.resolve("v2"))) {
            long t = T0;
            Path file = ParquetTables.partitionFile(root.resolve("v2"), "duration_event", "default", "pod", "app", "abc", t, "events");
            ParquetTables.write(catalog.connection(), file, "duration_event",
                    List.of("namespace", "pod", "container", "jvm_instance_id", "t", "type", "dur_ms", "target"),
                    List.of(
                            List.of("default", "pod", "app", "abc", t, "jdk.Compilation", 1.0, "b"),
                            List.of("default", "pod", "app", "abc", t, "jdk.Compilation", 1.0, "a")));
            V2ReadService service = new V2ReadService(catalog.connection(), root.resolve("v2"));
            V2ReadService.Identity id = new V2ReadService.Identity("default", "pod", "app", "abc");
            V2Window.Range range = new V2Window.Range(START, START.plusSeconds(1));
            Map<String, Object> first = service.events(id, range, "compile", null, null, 1);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) first.get("rows");
            assertEquals(1, rows.size());
            assertEquals("a", rows.get(0).get("target"));
            Map<String, Object> second = service.events(id, range, "compile", null, String.valueOf(first.get("next_cursor")), 1);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rest = (List<Map<String, Object>>) second.get("rows");
            assertEquals(1, rest.size());
            assertEquals("b", rest.get(0).get("target"));
            assertThrows(IllegalArgumentException.class, () -> service.events(id, range, "compile", null, "not-a-cursor", 1));
        }
    }

    @Test
    void headlinesUseTheWindowMaxAndHumongousRatioIsPerGc() throws Exception {
        Path root = Files.createTempDirectory("jafra-v2-headline");
        Path v2 = root.resolve("v2");
        try (IngestCatalog catalog = new IngestCatalog(v2)) {
            long later = T0 + 60_000_000_000L;
            Path minute = ParquetTables.partitionFile(v2, "rollup_1m", "default", "pod", "app", "abc", T0, "bucket-peaks");
            ParquetTables.write(catalog.connection(), minute, "rollup_1m", columns(), List.of(
                    rollup("gc.pause_ms", T0, 50),
                    rollup("gc.pause_ms", later, 10),
                    rollup("container.memory_usage", T0, 900),
                    rollup("container.memory_usage", later, 100)));
            Path heap = ParquetTables.partitionFile(v2, "heap_gc", "default", "pod", "app", "abc", T0, "heap");
            ParquetTables.write(catalog.connection(), heap, "heap_gc",
                    List.of("namespace", "pod", "container", "jvm_instance_id", "t", "kind", "collector", "cause", "heap_after",
                            "committed", "survivor_after", "young_capacity", "tenuring_threshold", "humongous_regions",
                            "promoted_bytes", "gc_id"),
                    List.of(
                            heapRow(T0, 1L, 20L),
                            heapRow(T0 + 1, 2L, 1L)));
            Path regions = ParquetTables.partitionFile(v2, "g1_region_frame", "default", "pod", "app", "abc", T0, "regions");
            ParquetTables.write(catalog.connection(), regions, "g1_region_frame",
                    List.of("namespace", "pod", "container", "jvm_instance_id", "t", "gc_id", "type", "region_count", "used"),
                    List.of(
                            List.of("default", "pod", "app", "abc", T0, 1L, "humongous", 100L, 1L),
                            List.of("default", "pod", "app", "abc", T0, 2L, "eden", 10_000L, 1L)));
            Path identity = ParquetTables.partitionFile(v2, "jvm_instance", "default", "pod", "app", "abc", T0, "jvm");
            ParquetTables.write(catalog.connection(), identity, "jvm_instance",
                    List.of("namespace", "pod", "container", "jvm_instance_id", "t", "max_heap_bytes"),
                    List.of(List.of("default", "pod", "app", "abc", T0, 1_000L)));
            List<List<Object>> rising = new java.util.ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                rising.add(java.util.Arrays.asList("default", "pod", "app", "abc", T0 + i, "young", "G1", "", (double) (i * 8), 100L,
                        1L, 10L, 5, 0L, 0L, 100L + i));
            }
            Path leak = ParquetTables.partitionFile(v2, "heap_gc", "default", "pod", "app", "abc", T0, "leak");
            ParquetTables.write(catalog.connection(), leak, "heap_gc",
                    List.of("namespace", "pod", "container", "jvm_instance_id", "t", "kind", "collector", "cause", "heap_after",
                            "committed", "survivor_after", "young_capacity", "tenuring_threshold", "humongous_regions",
                            "promoted_bytes", "gc_id"),
                    rising);

            List<List<Object>> tightHeap = new java.util.ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                tightHeap.add(java.util.Arrays.asList("default", "pod", "app", "tight", T0 + i, "young", "G1", "", (double) (i * 8),
                        100L, 1L, 10L, 5, 0L, 0L, 200L + i));
            }
            Path tight = ParquetTables.partitionFile(v2, "heap_gc", "default", "pod", "app", "tight", T0, "tight-heap");
            ParquetTables.write(catalog.connection(), tight, "heap_gc",
                    List.of("namespace", "pod", "container", "jvm_instance_id", "t", "kind", "collector", "cause", "heap_after",
                            "committed", "survivor_after", "young_capacity", "tenuring_threshold", "humongous_regions",
                            "promoted_bytes", "gc_id"),
                    tightHeap);
            Path tightJvm = ParquetTables.partitionFile(v2, "jvm_instance", "default", "pod", "app", "tight", T0, "tight-jvm");
            ParquetTables.write(catalog.connection(), tightJvm, "jvm_instance",
                    List.of("namespace", "pod", "container", "jvm_instance_id", "t", "max_heap_bytes"),
                    List.of(List.of("default", "pod", "app", "tight", T0, 90L)));

            V2ReadService service = new V2ReadService(catalog.connection(), v2);
            V2ReadService.Identity id = new V2ReadService.Identity("default", "pod", "app", "abc");
            V2Window.Range range = new V2Window.Range(START, START.plusSeconds(120));
            Map<String, Object> overview = service.overview(id, range, false);
            @SuppressWarnings("unchecked")
            Map<String, Object> blocks = (Map<String, Object>) overview.get("blocks");
            @SuppressWarnings("unchecked")
            Map<String, Object> gc = (Map<String, Object>) ((Map<String, Object>) blocks.get("gc")).get("headline_metrics");
            @SuppressWarnings("unchecked")
            Map<String, Object> memory = (Map<String, Object>) ((Map<String, Object>) blocks.get("container")).get("headline_metrics");
            assertEquals(50.0, ((Number) gc.get("gc.pause_ms.p99")).doubleValue());
            assertEquals(900.0, ((Number) memory.get("container.memory_usage.max")).doubleValue());

            Map<String, Object> findings = service.findings(id, range);
            assertTrue(codes(findings).contains("HUMONGOUS_ALLOC"));
            assertFalse(codes(findings).contains("MEMORY_LEAK"));
            Map<String, Object> tightFindings = service.findings(new V2ReadService.Identity("default", "pod", "app", "tight"), range);
            assertTrue(codes(tightFindings).contains("MEMORY_LEAK"));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> codes(Map<String, Object> findings) {
        List<String> codes = new java.util.ArrayList<>();
        for (Map<String, Object> finding : (List<Map<String, Object>>) findings.get("findings")) {
            codes.add(String.valueOf(finding.get("code")));
        }
        return codes;
    }

    private static List<Object> heapRow(long t, long gcId, long humongous) {
        return java.util.Arrays.asList("default", "pod", "app", "abc", t, "young", "G1", "", 10.0, 100L, 1L, 10L, 5, humongous, 0L, gcId);
    }

    private static void writeSeries(IngestCatalog catalog, Path v2) throws Exception {
        List<List<Object>> seconds = new ArrayList<>();
        for (int second = 0; second < 240; second++) {
            if (second == 1) {
                continue;
            }
            long t = T0 + second * 1_000_000_000L;
            seconds.add(rollup("cpu.jvm_user", t, 1.25));
            seconds.add(rollup("gc.pause_ms", t, 12.34));
            seconds.add(rollup("threads.live", t, 4));
            seconds.add(rollup("compile.ms", t, 3));
        }
        Path file = ParquetTables.partitionFile(v2, "rollup_1s", "default", "pod", "app", "abc", T0, "bucket-" + T0);
        ParquetTables.write(catalog.connection(), file, "rollup_1s", columns(), seconds);
        Path minute = ParquetTables.partitionFile(v2, "rollup_1m", "default", "pod", "app", "abc", T0, "bucket-" + T0);
        ParquetTables.write(catalog.connection(), minute, "rollup_1m", columns(), List.of(
                rollup("cpu.jvm_user", T0, 1.25),
                rollup("gc.pause_ms", T0, 12.34),
                rollup("alloc.bytes", T0, 1000),
                rollup("threads.live", T0, 4),
                rollup("compile.ms", T0, 3),
                rollup("container.memory_usage", T0, 1000),
                rollup("lock.enter_ms", T0, 2),
                rollup("safepoint.ms", T0, 1),
                rollup("io.socket_read_ms", T0, 8),
                rollup("exceptions.throwables", T0, 1),
                rollup("classes.loaded", T0, 2)));
    }

    private static void writeTop(IngestCatalog catalog, Path v2, long n) throws Exception {
        Path file = ParquetTables.partitionFile(v2, "top_1m", "default", "pod", "app", "abc", T0, "top-" + T0);
        ParquetTables.write(catalog.connection(), file, "top_1m",
                List.of("namespace", "pod", "container", "jvm_instance_id", "t", "kind", "grp", "key", "stack_id", "weight", "n"),
                List.of(List.of("default", "pod", "app", "abc", T0, "cpu", "method", "Cache.get", "stack1", 10.0, n)));
    }

    private static List<Object> rollup(String metric, long t, double value) {
        return List.of("default", "pod", "app", "abc", t, metric, t, 1L, value, value, value, value, value, value, value);
    }

    private static List<String> columns() {
        return List.of("namespace", "pod", "container", "jvm_instance_id", "t", "metric", "bucket_t", "n", "sum", "min", "max", "p50",
                "p90", "p99", "last");
    }
}

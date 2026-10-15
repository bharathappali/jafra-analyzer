package io.jafra.analyzer.api.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        }
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

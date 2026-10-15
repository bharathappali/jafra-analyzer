package io.jafra.analyzer.api.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.ingest.decode.IngestCatalog;
import io.jafra.analyzer.ingest.decode.ParquetTables;

class HeapReadTest {
    @Test
    void heapReturnsTheSerialPictureAndRegionCounts() throws Exception {
        Path root = Files.createTempDirectory("jafra-heap");
        long t = Instant.parse("2026-09-12T12:00:00Z").getEpochSecond() * 1_000_000_000L;
        try (IngestCatalog catalog = new IngestCatalog(root.resolve("v2"))) {
            Path heap = ParquetTables.partitionFile(root.resolve("v2"), "heap_gc", "default", "pod", "app", "abc", t, "heap");
            ParquetTables.write(catalog.connection(), heap, "heap_gc", List.of(
                    "namespace", "pod", "container", "jvm_instance_id", "t", "gc_id", "name", "cause", "kind", "collector", "estimated",
                    "heap_before", "heap_after", "committed", "eden_before", "eden_after", "survivor_before", "survivor_after",
                    "old_before", "old_after", "young_capacity", "humongous_regions", "promoted_bytes", "tenuring_threshold"),
                    List.of(row("default", "pod", "app", "abc", t, 7L, "Serial", "Allocation Failure", "young", "Serial", true,
                            300L, 80L, 300L, null, null, null, null, null, null, 100L, null, null, 15)));
            Path regions = ParquetTables.partitionFile(root.resolve("v2"), "g1_region_frame", "default", "pod", "app", "abc", t, "regions");
            ParquetTables.write(catalog.connection(), regions, "g1_region_frame",
                    List.of("namespace", "pod", "container", "jvm_instance_id", "t", "gc_id", "type", "region_count", "used"),
                    List.of(List.of("default", "pod", "app", "abc", t, 7L, "Humongous", 2L, 140L)));
            V2ReadService service = new V2ReadService(catalog.connection(), root.resolve("v2"));
            V2Window.Range range = new V2Window.Range(Instant.parse("2026-09-12T12:00:00Z"), Instant.parse("2026-09-12T12:01:00Z"));
            Map<String, Object> body = service.heap(new V2ReadService.Identity("default", "pod", "app", "abc"), range, true);
            assertEquals("ok", body.get("status"));
            assertEquals("Serial", body.get("collector"));
            @SuppressWarnings("unchecked")
            Map<String, Object> row = ((List<Map<String, Object>>) body.get("collections")).get(0);
            assertEquals(true, row.get("estimated"));
            assertEquals(100L, ((Number) row.get("young_capacity")).longValue());
            @SuppressWarnings("unchecked")
            Map<String, Object> regionsBody = (Map<String, Object>) body.get("regions");
            assertEquals("ok", regionsBody.get("status"));
            assertTrue(((List<?>) regionsBody.get("frames")).size() == 1);
        }
    }

    private static List<Object> row(Object... values) {
        List<Object> row = new ArrayList<>();
        for (Object value : values) {
            row.add(value);
        }
        return row;
    }
}

package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class HeapFramesTest {
    @Test
    void serialUsesHeapTotalsAndMarksTheYoungCapacityEstimated() {
        HeapFrames.Result result = HeapFrames.build(
                List.of(gc(7L)),
                "DefNew",
                "SerialOld",
                2L,
                List.of(new HeapFrames.HeapSummary(7L, true, 300L, 300L), new HeapFrames.HeapSummary(7L, false, 80L, 300L)),
                List.of(),
                List.of(new HeapFrames.YoungGc(7L, 15)),
                List.of(),
                List.of());
        HeapFrames.Row row = result.collections().get(0);
        assertEquals("Serial", row.collector());
        assertTrue(row.estimated());
        assertEquals(300L, row.heapBefore());
        assertEquals(80L, row.heapAfter());
        assertEquals(100L, row.youngCapacity());
        assertEquals(15, row.tenuring());
        assertNull(row.edenBefore());
    }

    @Test
    void g1AndParallelKeepExactGenerationsAndPromotion() {
        HeapFrames.Result g1 = HeapFrames.build(
                List.of(gc(1L)),
                "G1",
                "G1",
                null,
                List.of(new HeapFrames.HeapSummary(1L, true, 100L, 200L), new HeapFrames.HeapSummary(1L, false, 90L, 200L)),
                List.of(
                        new HeapFrames.GenerationSummary(1L, true, "g1", 40L, 10L, null),
                        new HeapFrames.GenerationSummary(1L, false, "g1", 5L, 8L, null)),
                List.of(),
                List.of(),
                List.of(
                        new HeapFrames.RegionEvent(1L, 1L, "Humongous", 100L),
                        new HeapFrames.RegionEvent(1L, 1L, "Humongous", 40L),
                        new HeapFrames.RegionEvent(1L, 1L, "Eden", 10L)));
        HeapFrames.Row row = g1.collections().get(0);
        assertEquals("G1", row.collector());
        assertFalse(row.estimated());
        assertEquals(50L, row.oldBefore());
        assertEquals(77L, row.oldAfter());
        assertEquals(27L, row.promotedBytes());
        assertEquals(2L, row.humongousRegions());
        assertEquals(2L, g1.regions().get(0).count());

        HeapFrames.Result parallel = HeapFrames.build(
                List.of(gc(3L)),
                "PSNew",
                "PSOld",
                null,
                List.of(),
                List.of(
                        new HeapFrames.GenerationSummary(3L, true, "parallel", 20L, 4L, 70L),
                        new HeapFrames.GenerationSummary(3L, false, "parallel", 2L, 6L, 80L)),
                List.of(),
                List.of(),
                List.of());
        HeapFrames.Row ps = parallel.collections().get(0);
        assertEquals("Parallel", ps.collector());
        assertFalse(ps.estimated());
        assertEquals(10L, ps.promotedBytes());
    }

    @Test
    void zgcYoungAndOldStaySeparateFromThePauseTable() {
        HeapFrames.Result result = HeapFrames.build(
                List.of(),
                "ZGC",
                "ZGC",
                null,
                List.of(new HeapFrames.HeapSummary(4L, true, 10L, 20L), new HeapFrames.HeapSummary(4L, false, 8L, 20L)),
                List.of(),
                List.of(),
                List.of(new HeapFrames.ZCollection(9L, 4L, "z_young"), new HeapFrames.ZCollection(11L, 5L, "z_old")),
                List.of());
        assertEquals(2, result.collections().size());
        assertEquals("Z", result.collections().get(0).collector());
        assertEquals("z_young", result.collections().get(0).kind());
        assertEquals(10L, result.collections().get(0).heapBefore());
        assertEquals("z_old", result.collections().get(1).kind());
    }

    private static JfrChunkReader.GcRow gc(long id) {
        return new JfrChunkReader.GcRow(id, id, "gc", "Allocation Failure", "young", 1.0, 1.0);
    }
}

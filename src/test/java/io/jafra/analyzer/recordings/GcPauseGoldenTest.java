package io.jafra.analyzer.recordings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.ItemFilters;

import io.jafra.analyzer.storage.ChunkStore;
import io.jafra.analyzer.test.ChunkFixtureLoader;
import io.jafra.analyzer.test.GoldenWorkloadQuery;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class GcPauseGoldenTest {
    @Inject
    ChunkStore store;

    @Inject
    RecordingCatalog catalog;

    @Inject
    JfrAggregationService aggregation;

    @BeforeEach
    void loadGoldenChunks() throws Exception {
        ChunkFixtureLoader.loadAll(store);
    }

    @Test
    void pauseCountMatchesDistinctGcIdsAndSumOfPauses() throws Exception {
        ReportWindow window = new ReportWindow(
                java.time.Instant.parse(GoldenWorkloadQuery.FROM),
                java.time.Instant.parse(GoldenWorkloadQuery.TO),
                null);
        RecordingCatalog.WindowSelection selection = catalog.openChunkWindow(
                        GoldenWorkloadQuery.NAMESPACE,
                        GoldenWorkloadQuery.POD,
                        GoldenWorkloadQuery.CONTAINER,
                        window)
                .orElseThrow();
        IItemCollection events = JfrEventSummarizer.loadEvents(selection);
        Set<Long> gcIds = new HashSet<>();
        long pauseCount = 0L;
        for (IItemIterable iterable : events.apply(ItemFilters.type("jdk.GCPhasePause"))) {
            for (IItem item : iterable) {
                Long duration = JfrItemAccess.durationNs(item);
                if (duration == null || duration <= 0) {
                    continue;
                }
                pauseCount++;
                Long gcId = JfrItemAccess.quantityLong(item, "gcId");
                if (gcId != null) {
                    gcIds.add(gcId);
                }
            }
        }
        double sumOfPausesMs = 0.0;
        for (IItemIterable iterable : events.apply(ItemFilters.type("jdk.GarbageCollection"))) {
            for (IItem item : iterable) {
                Long sumNs = JfrItemAccess.quantityLong(item, "sumOfPauses");
                if (sumNs != null && sumNs > 0) {
                    sumOfPausesMs += sumNs / 1_000_000.0;
                }
            }
        }
        JfrAggregationService.AggregationDocument document =
                aggregation.aggregate(selection, "gc-pauses", null, null, null);
        Number count = (Number) document.stats().get("count");
        Number sumMs = (Number) document.stats().get("sumMs");
        assertEquals(gcIds.size(), count.longValue());
        assertEquals(pauseCount, count.longValue());
        assertTrue(sumOfPausesMs > 0.0);
        double delta = Math.abs(sumMs.doubleValue() - sumOfPausesMs);
        assertTrue(delta <= sumOfPausesMs * 0.01, "sumMs " + sumMs + " vs sumOfPauses " + sumOfPausesMs);
    }
}

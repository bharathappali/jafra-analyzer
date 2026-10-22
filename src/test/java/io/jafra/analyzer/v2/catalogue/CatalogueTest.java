package io.jafra.analyzer.v2.catalogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.rollup.extract.Extractors;

class CatalogueTest {
    private final Catalogue catalogue = Catalogue.load();

    @Test
    void countsAndReferencesMatchTheFrozenCatalogue() {
        assertEquals("v2.0", catalogue.version());
        assertEquals("2026-10-22", catalogue.frozen());
        assertEquals(18, catalogue.groups().size());
        assertEquals(
                java.util.List.of(
                        "cpu", "heap", "gc", "metaspace", "codecache", "nmt", "container", "threads", "locks", "vt",
                        "safepoint", "jit", "classes", "socket", "file", "exceptions", "alloc", "endpoint"),
                catalogue.groups());
        assertEquals(121, catalogue.metrics().size());
        assertEquals(19, catalogue.events().size());
        assertEquals(5, catalogue.frameKinds().size());
        assertEquals(37, catalogue.charts().size());
        assertEquals(18, catalogue.overviewMetrics().size());
        assertEquals(9, catalogue.trendMetrics().size());
        assertEquals(9, catalogue.histogramMetrics().size());
        Set<String> families = catalogue.histogramMetrics().stream()
                .map(MetricSpec::histogramFamily)
                .collect(Collectors.toSet());
        assertEquals(7, families.size());
        for (MetricSpec metric : catalogue.metrics()) {
            assertTrue(catalogue.groups().contains(metric.group()), metric.name());
        }
        for (ChartSpec chart : catalogue.charts()) {
            for (String metric : chart.metrics()) {
                assertNotNull(catalogue.metric(metric), chart.id() + " " + metric);
            }
            for (String event : chart.events()) {
                assertNotNull(catalogue.event(event), chart.id() + " " + event);
            }
            for (String frame : chart.frames()) {
                assertNotNull(catalogue.frameKind(frame), chart.id() + " " + frame);
            }
        }
        Set<String> names = catalogue.metrics().stream().map(MetricSpec::name).collect(Collectors.toCollection(HashSet::new));
        assertEquals(names, Extractors.NAMES);
    }
}

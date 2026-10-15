package io.jafra.analyzer.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class MetricRegistryTest {
    @Test
    void cataloguesUnitKindPeriodAndMinimumJdk() {
        MetricRegistry.Metric rss = MetricRegistry.get("rss");
        assertNotNull(rss);
        assertEquals("bytes", rss.unit());
        assertEquals("gauge", rss.kind());
        assertEquals("1s", rss.nativePeriod());
        assertEquals(21, rss.minJdk());
        assertEquals("jdk.ResidentSetSize", rss.sourceEvents().get(0));

        MetricRegistry.Metric pause = MetricRegistry.get("gc.pause_ms");
        assertEquals("duration", pause.kind());
        assertEquals("1ms", pause.nativePeriod());
        assertEquals("io.socket_read_ms", MetricRegistry.durationMetric("jdk.SocketRead"));
    }
}

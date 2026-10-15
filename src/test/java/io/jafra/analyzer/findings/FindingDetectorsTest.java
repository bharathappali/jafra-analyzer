package io.jafra.analyzer.findings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FindingDetectorsTest {
    @Test
    void aQuietWindowIsHealthy() {
        WindowSnapshot snapshot = observed();
        assertEquals(List.of("HEALTHY"), codes(FindingDetectors.evaluate(snapshot)));
    }

    @Test
    void pressureLeakPromotionAndStormsFireOnTheirPredicates() {
        WindowSnapshot pressure = observed();
        pressure.pauseMs = 20_000;
        pressure.youngGcs = 200;
        pressure.heapAfter.add(800.0);
        pressure.heapMax = 1000L;
        assertTrue(codes(FindingDetectors.evaluate(pressure)).contains("GC_PRESSURE"));

        WindowSnapshot leak = observed();
        leak.heapMax = 100L;
        for (int i = 0; i < 10; i++) {
            leak.heapAfter.add(50.0 + i * 4);
        }
        assertTrue(codes(FindingDetectors.evaluate(leak)).contains("MEMORY_LEAK"));

        WindowSnapshot promotion = observed();
        promotion.lowTenuringGcs = 5;
        promotion.survivorFullGcs = 5;
        promotion.promotedBytes = 50;
        promotion.allocBytes = 100;
        assertTrue(codes(FindingDetectors.evaluate(promotion)).contains("PREMATURE_PROMOTION"));

        WindowSnapshot storm = observed();
        storm.windowNs = 1_000_000_000L;
        storm.throwables = 20_000;
        assertTrue(codes(FindingDetectors.evaluate(storm)).contains("EXCEPTION_STORM"));

        WindowSnapshot oom = observed();
        oom.memoryUsage = 950;
        oom.memLimit = 1000.0;
        assertTrue(codes(FindingDetectors.evaluate(oom)).contains("CONTAINER_OOM_RISK"));

        WindowSnapshot serial = observed();
        serial.ergonomicSerial = true;
        List<String> codes = codes(FindingDetectors.evaluate(serial));
        assertTrue(codes.contains("ERGONOMIC_SERIAL_GC"));
        assertTrue(!codes.contains("HEALTHY"));
    }

    private static WindowSnapshot observed() {
        WindowSnapshot snapshot = new WindowSnapshot();
        snapshot.observed = true;
        snapshot.windowNs = 10_000_000_000L;
        return snapshot;
    }

    private static List<String> codes(List<Map<String, Object>> findings) {
        return findings.stream().map(finding -> String.valueOf(finding.get("code"))).toList();
    }
}

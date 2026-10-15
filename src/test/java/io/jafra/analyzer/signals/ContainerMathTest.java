package io.jafra.analyzer.signals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ContainerMathTest {
    @Test
    void derivesLimitsRequestsAndSerialErgonomics() {
        assertEquals(2.0, ContainerMath.cpuLimitCores(200_000L, 100_000L));
        assertNull(ContainerMath.cpuLimitCores(-1L, 100_000L));
        assertEquals(1000L, ContainerMath.cpuRequestMillicores(1024L));
        assertEquals(0L, ContainerMath.cpuRequestMillicores(2L));
        assertEquals("best_effort", ContainerMath.cpuQos(2L));
        assertEquals("approx", ContainerMath.requestQuality("cgroupv1"));
        assertEquals("approx_v2_weight_mapping", ContainerMath.requestQuality("cgroupv2"));
        assertNull(ContainerMath.memLimit(-1L));

        List<ContainerMath.Flag> ergonomic = List.of(new ContainerMath.Flag("UseSerialGC", "true", "ergonomic"));
        assertEquals("ergonomic", ContainerMath.gcSelectedBy(true, ergonomic));
        assertTrue(ContainerMath.ergonomicSerial("ergonomic", true, 1L, 2L * 1024 * 1024 * 1024));
        assertTrue(ContainerMath.ergonomicSerial("ergonomic", true, 4L, 1024L * 1024 * 1024));
        assertFalse(ContainerMath.ergonomicSerial("ergonomic", true, 2L, 2L * 1024 * 1024 * 1024));
        assertFalse(ContainerMath.ergonomicSerial(
                "command_line", true, 1L, 1024L * 1024 * 1024));
        assertEquals("command_line", ContainerMath.gcSelectedBy(true,
                List.of(new ContainerMath.Flag("UseG1GC", "true", "Command line"))));
        assertEquals("Serial", ContainerMath.gcFamily("Serial", "Serial", Map.of()));
        assertEquals("G1", ContainerMath.gcFamily(null, null, Map.of("UseG1GC", "true")));
    }
}

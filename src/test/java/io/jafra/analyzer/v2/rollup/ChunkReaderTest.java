package io.jafra.analyzer.v2.rollup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ChunkReaderTest {
    @Test
    void majorComesFromTheParentheses() {
        assertEquals(21, ChunkReader.major(
                "OpenJDK 64-Bit Server VM (21.0.12+7-LTS) for linux-amd64 JRE (21.0.12+7-LTS), built on ..."));
        assertEquals(17, ChunkReader.major("OpenJDK 64-Bit Server VM (17.0.20+8) ..."));
        assertEquals(25, ChunkReader.major("OpenJDK 64-Bit Server VM (25.0.1+9) ..."));
        assertEquals(8, ChunkReader.major("Java HotSpot(TM) 64-Bit Server VM (1.8.0_412-b08) ..."));
        assertEquals(0, ChunkReader.major("not a jvm version"));
        assertEquals(0, ChunkReader.major("OpenJDK 64-Bit Server VM"));
    }

    @Test
    void systemPropertyFillsAMissingParenthesis() {
        Seen jvm = new Seen(
                "jdk.JVMInformation",
                0L,
                0L,
                "",
                Map.of("jvmVersion", "garbage", "jvmStartTime", 5_000d),
                List.of());
        Seen property = new Seen(
                "jdk.InitialSystemProperty",
                0L,
                0L,
                "",
                Map.of("key", "java.version", "value", "17.0.20"),
                List.of());
        ChunkFacts facts = new ChunkReader(10d).assemble(List.of(jvm, property), Map.of(), 1_000L, 6_000L);
        assertEquals(17, facts.jdkMajor);
        assertTrue(facts.sawJvmInformation);
        assertEquals(5_000L, facts.jvmStartMs);
    }

    @Test
    void missingJvmInformationDoesNotInventAJdkOrStart() {
        Seen cpu = new Seen("jdk.CPULoad", 1_000L, 1_000L, "", Map.of("jvmUser", 0.1d), List.of());
        ChunkFacts facts = new ChunkReader(10d).assemble(List.of(cpu), Map.of(1L, "jdk.CPULoad"), 1_000L, 6_000L);
        assertEquals(0, facts.jdkMajor);
        assertEquals(0L, facts.jvmStartMs);
        assertFalse(facts.sawJvmInformation);
    }

    @Test
    void unsetLongsAreNullAndOsIsPrettyName() {
        double unset = (double) Long.MIN_VALUE;
        Seen jvm = new Seen("jdk.JVMInformation", 0L, 0L, "", Map.of(
                "jvmVersion", "OpenJDK 64-Bit Server VM (21.0.12+7-LTS)",
                "jvmStartTime", 5_000d), List.of());
        Seen gc = new Seen("jdk.GCConfiguration", 0L, 0L, "", Map.of(
                "youngCollector", "G1",
                "oldCollector", "G1",
                "parallelGCThreads", 4d,
                "pauseTarget", unset), List.of());
        Seen young = new Seen("jdk.YoungGenerationConfiguration", 0L, 0L, "", Map.of("maxSize", unset, "minSize", 1024d), List.of());
        Seen os = new Seen("jdk.OSInformation", 0L, 0L, "", Map.of(
                "osVersion", "NAME=\"Ubuntu\"\nPRETTY_NAME=\"Ubuntu 24.04.1 LTS\"\n"), List.of());
        ChunkFacts facts = new ChunkReader(10d).assemble(List.of(jvm, gc, young, os), Map.of(), 0L, 5_000L);
        assertTrue(facts.settingsJson.contains("\"pause_target_ms\":null"));
        assertTrue(facts.settingsJson.contains("\"young_max\":null"));
        assertTrue(facts.settingsJson.contains("\"parallel_threads\":4"));
        assertTrue(!facts.settingsJson.contains("\"parallel_threads\":4.0"));
        assertTrue(facts.settingsJson.contains("Ubuntu 24.04.1 LTS"));
        assertEquals("Ubuntu 24.04.1 LTS", ChunkReader.prettyName("PRETTY_NAME=\"Ubuntu 24.04.1 LTS\""));
    }
}

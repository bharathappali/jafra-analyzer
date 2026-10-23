package io.jafra.analyzer.v2.incidents;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.incidents.support.InternalInputs;
import io.jafra.analyzer.v2.rollup.ChunkFacts;
import io.jafra.analyzer.v2.rollup.ChunkReader;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.jafra.analyzer.v2.rollup.extract.Extractors;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

class InternalInputsRecordingTest {
    @Test
    void realRecordingFeedsInternalInputs() throws Exception {
        Path dump = Files.createTempFile("jafra-internal", ".jfr");
        long startMs = System.currentTimeMillis() - 1_000L;
        boolean methodTiming = true;
        try (Recording recording = new Recording()) {
            recording.enable("jdk.JVMInformation");
            recording.enable("jdk.DirectBufferStatistics").withPeriod(Duration.ofMillis(50));
            recording.enable("jdk.ClassLoaderStatistics").withPeriod(Duration.ofMillis(50));
            recording.enable("jdk.InitialSystemProperty");
            try {
                recording.enable("jdk.MethodTiming");
            } catch (RuntimeException error) {
                methodTiming = false;
                System.out.println("jdk.MethodTiming skipped: " + error.getMessage());
            }
            recording.start();
            ByteBuffer buffer = ByteBuffer.allocateDirect(4096);
            if (buffer.capacity() < 0) {
                return;
            }
            ClassLoader loader = new java.net.URLClassLoader(new java.net.URL[0], getClass().getClassLoader());
            loader.loadClass(String.class.getName());
            Thread.sleep(80);
            recording.stop();
            recording.dump(dump);
        }
        System.out.println("RedefineClasses skipped: no Instrumentation agent is attached");
        try (RecordingFile file = new RecordingFile(dump)) {
            ChunkFacts facts = new ChunkReader(10d).read(file, startMs, startMs + 5_000L);
            WindowData out = new WindowData();
            out.dtSeconds = facts.dtSeconds;
            Extractors.extract(facts, out);
            assertTrue(facts.eventTypes.contains("jdk.DirectBufferStatistics")
                    || facts.ofType("jdk.DirectBufferStatistics").size() > 0);
            assertTrue(out.internalSeries().stream().anyMatch(point ->
                    InternalInputs.CHUNK_EVENT_TYPES.equals(point.name())));
            assertTrue(
                    facts.ofType("jdk.DirectBufferStatistics").isEmpty()
                            || out.internalSeries().stream().anyMatch(point ->
                                    InternalInputs.DIRECT_MAX_BYTES.equals(point.name()) && point.value() > 0d),
                    "direct buffer max missing");
            assertTrue(
                    facts.ofType("jdk.ClassLoaderStatistics").isEmpty()
                            || out.internalSeries().stream().anyMatch(point ->
                                    InternalInputs.LOADER_COUNT.equals(point.name())),
                    "loader count missing");
            if (methodTiming && !facts.ofType("jdk.MethodTiming").isEmpty()) {
                assertTrue(out.internalSeries().stream().anyMatch(point ->
                        InternalInputs.METHOD_TIMING_RAW.equals(point.name())
                                && point.key().contains(";field=")));
            } else if (!methodTiming) {
                System.out.println("I9 skipped: jdk.MethodTiming is not on this JDK");
            }
        } finally {
            Files.deleteIfExists(dump);
        }
    }
}

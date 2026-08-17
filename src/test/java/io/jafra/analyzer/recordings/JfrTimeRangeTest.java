package io.jafra.analyzer.recordings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jafra.analyzer.storage.ChunkMetadata;

class JfrTimeRangeTest {
    @Test
    void walksConcatenatedChunkHeaders(@TempDir Path tmp) throws Exception {
        Instant start = Instant.parse("2026-08-17T09:00:00Z");
        byte[] first = chunk(100, start, Duration.ofSeconds(5));
        byte[] second = chunk(80, start.plusSeconds(5), Duration.ofSeconds(7));
        Path file = tmp.resolve("profile-0.jfr");
        Files.write(file, concat(first, second));

        JfrTimeRange.TimeSpan span = JfrTimeRange.ofFile(file);
        assertEquals(start, span.start());
        assertEquals(start.plusSeconds(12), span.stop());
        assertTrue(span.overlaps(start.plusSeconds(10), start.plusSeconds(13)));
        assertTrue(!span.overlaps(start.plusSeconds(12), start.plusSeconds(20)));
    }

    @Test
    void stillReadsTimesWhenClaimedChunkSizeExceedsFile(@TempDir Path tmp) throws Exception {
        Instant start = Instant.parse("2026-08-17T09:00:00Z");
        Path file = tmp.resolve("live.jfr");
        Files.write(file, JfrTimeRange.header(1_000_000, epochNanos(start), Duration.ofSeconds(5).toNanos()));
        JfrTimeRange.TimeSpan span = JfrTimeRange.ofFile(file);
        assertEquals(start, span.start());
        assertEquals(start.plusSeconds(5), span.stop());
    }

    @Test
    void aggregatesChunkMetadataTimes() {
        Instant start = Instant.parse("2026-08-17T09:00:00Z");
        ChunkMetadata early = timed("a", 0, start, Duration.ofSeconds(5));
        ChunkMetadata late = timed("b", 100, start.plusSeconds(5), Duration.ofSeconds(5));
        JfrTimeRange.TimeSpan span = JfrTimeRange.ofChunks(List.of(early, late));
        assertEquals(start, span.start());
        assertEquals(start.plusSeconds(10), span.stop());
    }

    @Test
    void ignoresPayloadsWithoutFlightRecorderHeaders(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("garbage.jfr");
        Files.write(file, new byte[] {1, 2, 3, 4});
        assertNull(JfrTimeRange.ofFile(file));
    }

    private static ChunkMetadata timed(String chunkId, long offset, Instant start, Duration duration) {
        return new ChunkMetadata(
                chunkId,
                "rec",
                "local-demo",
                "ns",
                "uid",
                "pod",
                "app",
                "profile-0.jfr",
                offset,
                10,
                "checksum",
                epochNanos(start),
                duration.toNanos());
    }

    private static byte[] chunk(int size, Instant start, Duration duration) {
        byte[] payload = new byte[size];
        byte[] header = JfrTimeRange.header(size, epochNanos(start), duration.toNanos());
        System.arraycopy(header, 0, payload, 0, header.length);
        return payload;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private static long epochNanos(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }
}

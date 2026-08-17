package io.jafra.analyzer.recordings;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collection;

import io.jafra.analyzer.storage.ChunkMetadata;

final class JfrTimeRange {
    static final int HEADER_SIZE = 68;
    private static final byte[] MAGIC = new byte[] {'F', 'L', 'R', 0};

    private JfrTimeRange() {}

    static TimeSpan ofFile(Path recording) {
        if (recording == null || !Files.isRegularFile(recording)) {
            return null;
        }
        try (FileChannel channel = FileChannel.open(recording, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < HEADER_SIZE) {
                return null;
            }
            ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            Instant start = null;
            Instant stop = null;
            long offset = 0;
            while (offset + HEADER_SIZE <= size) {
                header.clear();
                int read = channel.read(header, offset);
                if (read < HEADER_SIZE) {
                    break;
                }
                header.flip();
                ChunkTimes chunk = parseHeader(header);
                if (chunk == null || chunk.size() < HEADER_SIZE) {
                    break;
                }
                if (chunk.startNs() > 0) {
                    Instant chunkStart = fromEpochNanos(chunk.startNs());
                    Instant chunkStop = chunk.durationNs() > 0
                            ? fromEpochNanos(chunk.startNs() + chunk.durationNs())
                            : chunkStart;
                    start = earlier(start, chunkStart);
                    stop = later(stop, chunkStop);
                }
                if (offset + chunk.size() > size) {
                    break;
                }
                offset += chunk.size();
            }
            return TimeSpan.of(start, stop);
        } catch (IOException ignored) {
            return null;
        }
    }

    static TimeSpan ofChunks(Collection<ChunkMetadata> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return null;
        }
        Instant start = null;
        Instant stop = null;
        for (ChunkMetadata chunk : chunks) {
            if (chunk == null || chunk.chunkStartTimeNs() <= 0) {
                continue;
            }
            Instant chunkStart = fromEpochNanos(chunk.chunkStartTimeNs());
            Instant chunkStop = chunk.chunkDurationNs() > 0
                    ? fromEpochNanos(chunk.chunkStartTimeNs() + chunk.chunkDurationNs())
                    : chunkStart;
            start = earlier(start, chunkStart);
            stop = later(stop, chunkStop);
        }
        return TimeSpan.of(start, stop);
    }

    static Instant fromEpochNanos(long nanos) {
        long seconds = Math.floorDiv(nanos, 1_000_000_000L);
        long remainder = Math.floorMod(nanos, 1_000_000_000L);
        return Instant.ofEpochSecond(seconds, remainder);
    }

    static byte[] header(long chunkSize, long startTimeNs, long durationNs) {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        buffer.put(MAGIC);
        buffer.putInt(2);
        buffer.putLong(chunkSize);
        buffer.putLong(HEADER_SIZE);
        buffer.putLong(HEADER_SIZE);
        buffer.putLong(startTimeNs);
        buffer.putLong(durationNs);
        buffer.putLong(0);
        buffer.putLong(1_000_000_000L);
        buffer.putInt(0);
        return buffer.array();
    }

    private static ChunkTimes parseHeader(ByteBuffer header) {
        byte[] magic = new byte[4];
        header.get(magic);
        if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1] || magic[2] != MAGIC[2] || magic[3] != MAGIC[3]) {
            return null;
        }
        header.getInt();
        long chunkSize = header.getLong();
        header.getLong();
        header.getLong();
        long startNs = header.getLong();
        long durationNs = header.getLong();
        return new ChunkTimes(chunkSize, startNs, durationNs);
    }

    private static Instant earlier(Instant current, Instant candidate) {
        if (current == null || candidate.isBefore(current)) {
            return candidate;
        }
        return current;
    }

    private static Instant later(Instant current, Instant candidate) {
        if (current == null || candidate.isAfter(current)) {
            return candidate;
        }
        return current;
    }

    private record ChunkTimes(long size, long startNs, long durationNs) {}

    record TimeSpan(Instant start, Instant stop) {
        static TimeSpan of(Instant start, Instant stop) {
            if (start == null || stop == null) {
                return null;
            }
            if (stop.isBefore(start)) {
                return new TimeSpan(stop, start);
            }
            return new TimeSpan(start, stop);
        }

        boolean overlaps(Instant from, Instant to) {
            if (from == null || to == null) {
                return false;
            }
            if (!start.isBefore(stop)) {
                return !start.isBefore(from) && start.isBefore(to);
            }
            return start.isBefore(to) && stop.isAfter(from);
        }

        String startIso() {
            return start.toString();
        }

        String endIso() {
            return stop.toString();
        }
    }
}

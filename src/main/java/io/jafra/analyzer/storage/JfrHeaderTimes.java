package io.jafra.analyzer.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** First JFR chunk header in a payload file. Used when older meta has no start time. */
final class JfrHeaderTimes {
    private static final int HEADER_SIZE = 68;
    private static final byte[] MAGIC = new byte[] {'F', 'L', 'R', 0};

    private JfrHeaderTimes() {}

    static long[] read(Path payload) {
        if (payload == null || !Files.isRegularFile(payload)) {
            return null;
        }
        try (FileChannel channel = FileChannel.open(payload, StandardOpenOption.READ)) {
            if (channel.size() < HEADER_SIZE) {
                return null;
            }
            ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            if (channel.read(header, 0) < HEADER_SIZE) {
                return null;
            }
            header.flip();
            byte[] magic = new byte[4];
            header.get(magic);
            if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1] || magic[2] != MAGIC[2] || magic[3] != MAGIC[3]) {
                return null;
            }
            header.getInt();
            header.getLong();
            header.getLong();
            header.getLong();
            long startNs = header.getLong();
            long durationNs = header.getLong();
            if (startNs <= 0) {
                return null;
            }
            return new long[] {startNs, Math.max(0, durationNs)};
        } catch (IOException ignored) {
            return null;
        }
    }
}

package io.jafra.analyzer.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/**
 * Widens each stored chunk's event window from that chunk alone. A full queue drops the id; startup
 * scans rows whose decoded_at is still null.
 */
@ApplicationScoped
public class EventRangeDecoder {
    static final int QUEUE_CAPACITY = 256;

    private static final Logger LOG = Logger.getLogger(EventRangeDecoder.class);

    private final IndexStore index;
    private final Path recordingsDir;
    private final ThreadPoolExecutor executor;

    @Inject
    public EventRangeDecoder(
            IndexStore index,
            @ConfigProperty(name = "jafra.storage.root", defaultValue = "/var/lib/jafra/analyzer") String root,
            @ConfigProperty(name = "jafra.index.decode-threads", defaultValue = "1") int threads) {
        this(index, Path.of(root), threads);
    }

    public EventRangeDecoder(IndexStore index, Path storageRoot, int threads) {
        this.index = index;
        this.recordingsDir = storageRoot.resolve("data").resolve("recordings");
        int workers = Math.max(1, threads);
        this.executor = new ThreadPoolExecutor(
                workers,
                workers,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "jafra-index-decode");
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, pool) -> LOG.warnf("decode queue full, skipping a chunk"));
    }

    public void offer(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            return;
        }
        try {
            executor.execute(() -> decode(chunkId));
        } catch (RejectedExecutionException error) {
            LOG.warnf("decode queue full, skipping %s", chunkId);
        }
    }

    public void decode(String chunkId) {
        try {
            ChunkIndexRow row = index.get(chunkId);
            if (row == null || row.path() == null || row.path().isBlank()) {
                return;
            }
            Path file = recordingsDir.resolve(row.path());
            if (!Files.isRegularFile(file)) {
                index.markDecoded(chunkId);
                return;
            }
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            boolean any = false;
            try (RecordingFile recording = new RecordingFile(file)) {
                while (recording.hasMoreEvents()) {
                    RecordedEvent event = recording.readEvent();
                    if (event == null || event.getStartTime() == null) {
                        continue;
                    }
                    long start = epochNanos(event.getStartTime());
                    long end = start;
                    Duration duration = event.getDuration();
                    if (duration != null && !duration.isNegative() && !duration.isZero()) {
                        try {
                            end = Math.addExact(start, duration.toNanos());
                        } catch (ArithmeticException overflow) {
                            end = start;
                        }
                    }
                    any = true;
                    min = Math.min(min, start);
                    max = Math.max(max, end);
                }
            }
            if (any) {
                index.updateEventRange(chunkId, min, max);
            } else {
                index.markDecoded(chunkId);
            }
        } catch (RuntimeException | IOException error) {
            LOG.warnf(error, "unable to decode event range for %s", chunkId);
            try {
                index.markDecoded(chunkId);
            } catch (IOException ignored) {
                LOG.warnf(ignored, "unable to mark %s decoded", chunkId);
            }
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    private static long epochNanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
    }
}

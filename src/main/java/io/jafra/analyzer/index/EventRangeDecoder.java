package io.jafra.analyzer.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.jafra.analyzer.storage.ChunkSidecar;
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
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();

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
                new ThreadPoolExecutor.AbortPolicy());
    }

    public void offer(String chunkId) {
        if (chunkId == null || chunkId.isBlank() || !inflight.add(chunkId)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    decode(chunkId);
                } finally {
                    inflight.remove(chunkId);
                }
            });
        } catch (RejectedExecutionException error) {
            inflight.remove(chunkId);
            LOG.warnf("decode queue full, skipping %s", chunkId);
        }
    }

    /** Offers the next batch of stored chunks that still have no decoded_at. */
    public void rescan() {
        try {
            for (String chunkId : index.undecodedChunkIds(QUEUE_CAPACITY)) {
                offer(chunkId);
            }
        } catch (IOException error) {
            LOG.warnf(error, "decode rescan failed");
        }
    }

    public void awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (inflight.isEmpty() && executor.getActiveCount() == 0 && executor.getQueue().isEmpty()) {
                return;
            }
            Thread.sleep(10);
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
                syncSidecar(chunkId);
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

    private void syncSidecar(String chunkId) {
        try {
            ChunkIndexRow updated = index.get(chunkId);
            if (updated == null || updated.path() == null || updated.path().isBlank()) {
                return;
            }
            ChunkSidecar.syncEventRange(ChunkSidecar.beside(recordingsDir.resolve(updated.path())), updated);
        } catch (IOException error) {
            LOG.warnf(error, "unable to update sidecar for %s", chunkId);
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

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
import io.jafra.analyzer.storage.SidecarLocks;
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

    /** Runs after the chunk file is read and before the index or sidecar is updated. Tests use this to overlap retention. */
    static volatile Runnable beforeCommit = () -> {};

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
        ReadResult result;
        try {
            result = readEvents(chunkId);
        } catch (RuntimeException | IOException error) {
            LOG.warnf(error, "unable to decode event range for %s", chunkId);
            result = ReadResult.FAILED;
        }
        if (result.kind == Kind.ABSENT) {
            return;
        }
        beforeCommit.run();
        synchronized (SidecarLocks.of(chunkId)) {
            try {
                commit(chunkId, result);
            } catch (RuntimeException | IOException error) {
                LOG.warnf(error, "unable to record event range for %s", chunkId);
            }
        }
    }

    private ReadResult readEvents(String chunkId) throws IOException {
        ChunkIndexRow row = index.get(chunkId);
        if (row == null || row.path() == null || row.path().isBlank()) {
            return ReadResult.ABSENT;
        }
        Path file = recordingsDir.resolve(row.path());
        if (!Files.isRegularFile(file)) {
            return ReadResult.MISSING;
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
        return any ? new ReadResult(Kind.RANGE, min, max) : ReadResult.EMPTY;
    }

    /**
     * Re-reads the row under the per-chunk lock. Updates the index and an existing sidecar only
     * while the chunk is still stored and its payload is still on disk.
     */
    private void commit(String chunkId, ReadResult result) throws IOException {
        ChunkIndexRow row = storedPayload(chunkId);
        if (row == null) {
            return;
        }
        if (result.kind == Kind.RANGE) {
            index.updateEventRange(chunkId, result.min, result.max);
            row = storedPayload(chunkId);
            if (row == null) {
                return;
            }
            Path sidecar = ChunkSidecar.beside(recordingsDir.resolve(row.path()));
            if (!Files.isRegularFile(sidecar)) {
                return;
            }
            ChunkSidecar.syncEventRange(sidecar, row);
            return;
        }
        if (result.kind == Kind.EMPTY || result.kind == Kind.FAILED) {
            index.markDecoded(chunkId);
        }
    }

    /** The stored row when its payload file is still present. */
    private ChunkIndexRow storedPayload(String chunkId) throws IOException {
        ChunkIndexRow row = index.get(chunkId);
        if (row == null || !ChunkIndexRow.STATUS_STORED.equals(row.status())) {
            return null;
        }
        if (row.path() == null || row.path().isBlank()) {
            return null;
        }
        if (!Files.isRegularFile(recordingsDir.resolve(row.path()))) {
            return null;
        }
        return row;
    }

    private enum Kind {
        ABSENT,
        MISSING,
        FAILED,
        EMPTY,
        RANGE
    }

    private static final class ReadResult {
        static final ReadResult ABSENT = new ReadResult(Kind.ABSENT, 0, 0);
        static final ReadResult MISSING = new ReadResult(Kind.MISSING, 0, 0);
        static final ReadResult FAILED = new ReadResult(Kind.FAILED, 0, 0);
        static final ReadResult EMPTY = new ReadResult(Kind.EMPTY, 0, 0);

        final Kind kind;
        final long min;
        final long max;

        ReadResult(Kind kind, long min, long max) {
            this.kind = kind;
            this.min = min;
            this.max = max;
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

package io.jafra.analyzer.v2.rollup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.jafra.analyzer.index.ChunkIndexRow;
import io.jafra.analyzer.index.IndexStore;
import io.jafra.analyzer.storage.SidecarLocks;
import io.jafra.analyzer.v2.rollup.extract.Extractors;
import io.jafra.analyzer.v2.store.V2Store;
import jdk.jfr.consumer.RecordingFile;

/** Rolls one stored chunk into v2.duckdb. A full queue drops the id; rescan picks it up. */
@ApplicationScoped
public class RollupWriter {
    static final int QUEUE_CAPACITY = 256;

    private static final Logger LOG = Logger.getLogger(RollupWriter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Runs after the chunk is read and before the v2 rows are committed. Tests use this to delete the file. */
    public static volatile Runnable beforeCommit = () -> {};

    private final IndexStore index;
    private final V2Store store;
    private final Path recordingsDir;
    private final ChunkReader reader;
    private final ThreadPoolExecutor executor;
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();
    private final Event<WindowCommitted> committed;

    @Inject
    public RollupWriter(
            IndexStore index,
            V2Store store,
            Event<WindowCommitted> committed,
            @ConfigProperty(name = "jafra.storage.root", defaultValue = "/var/lib/jafra/analyzer") String root,
            @ConfigProperty(name = "jafra.v2.rollup-threads", defaultValue = "1") int threads,
            @ConfigProperty(name = "jafra.v2.ap.lock-interval-ms", defaultValue = "10") double lockIntervalMs) {
        this(index, store, Path.of(root), threads, lockIntervalMs, committed);
    }

    public RollupWriter(IndexStore index, V2Store store, Path storageRoot, int threads) {
        this(index, store, storageRoot, threads, 10d, null);
    }

    private RollupWriter(
            IndexStore index,
            V2Store store,
            Path storageRoot,
            int threads,
            double lockIntervalMs,
            Event<WindowCommitted> committed) {
        this.index = index;
        this.store = store;
        this.recordingsDir = storageRoot.resolve("data").resolve("recordings");
        this.reader = new ChunkReader(lockIntervalMs);
        this.committed = committed;
        int workers = Math.max(1, threads);
        this.executor = new ThreadPoolExecutor(
                workers,
                workers,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "jafra-v2-rollup");
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
                    process(chunkId);
                } finally {
                    inflight.remove(chunkId);
                }
            });
        } catch (RejectedExecutionException error) {
            inflight.remove(chunkId);
            LOG.warnf("rollup queue full, skipping %s", chunkId);
        }
    }

    /** Offers stored chunks that have no v2 window, oldest first, up to one queue batch. */
    public void rescan() {
        try {
            Set<String> done = new HashSet<>(store.processedChunkIds());
            List<ChunkIndexRow> rows = new ArrayList<>(index.storedRows());
            rows.sort((left, right) -> Long.compare(left.startNs(), right.startNs()));
            int offered = 0;
            for (ChunkIndexRow row : rows) {
                if (done.contains(row.chunkId())) {
                    continue;
                }
                offer(row.chunkId());
                if (++offered >= QUEUE_CAPACITY) {
                    break;
                }
            }
        } catch (IOException error) {
            LOG.warnf(error, "rollup rescan failed");
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

    public void process(String chunkId) {
        ChunkIndexRow row;
        try {
            row = index.get(chunkId);
        } catch (IOException error) {
            LOG.warnf(error, "unable to read index row %s", chunkId);
            return;
        }
        if (!stored(row)) {
            return;
        }
        Path file = recordingsDir.resolve(row.path());
        if (!Files.isRegularFile(file)) {
            return;
        }
        ChunkFacts facts = null;
        try (RecordingFile recording = new RecordingFile(file)) {
            facts = reader.read(recording, millis(row.startNs()), millis(row.endNs()));
        } catch (RuntimeException | IOException error) {
            LOG.warnf(error, "unable to roll up %s", chunkId);
        }
        beforeCommit.run();
        synchronized (SidecarLocks.of(chunkId)) {
            try {
                commit(chunkId, facts);
            } catch (RuntimeException | IOException error) {
                LOG.warnf(error, "unable to store rollup for %s", chunkId);
            }
        }
    }

    /** Synthetic windows for tests. The chunk id must not already be stored. */
    public V2Store.CommitResult commitSynthetic(String chunkId, WindowData data) throws IOException {
        if (data.derive == null) {
            data.derive = () -> {};
        }
        V2Store.CommitResult result = store.commit(chunkId, data);
        if (result.wrote() && committed != null) {
            committed.fire(new WindowCommitted(chunkId, result.jvmId(), data.fromMs, data.toMs, result.late()));
        }
        return result;
    }

    private void commit(String chunkId, ChunkFacts facts) throws IOException {
        ChunkIndexRow row = storedPayload(chunkId);
        if (row == null) {
            return;
        }
        WindowData data = new WindowData();
        data.clusterId = row.clusterId();
        data.namespace = row.namespace();
        data.podName = row.podName();
        data.podUid = row.podUid();
        data.containerName = row.containerName();
        data.fromMs = millis(row.startNs());
        data.toMs = Math.max(data.fromMs + 1, millis(row.endNs()));
        data.dtSeconds = Math.max(0.001d, (data.toMs - data.fromMs) / 1000d);
        if (facts == null) {
            data.jvmStartMs = data.fromMs;
            data.jdkMajor = Runtime.version().feature();
            data.profile = "custom";
            data.settingsJson = "{}";
        } else {
            data.jvmStartMs = facts.jvmStartMs == 0L ? data.fromMs : facts.jvmStartMs;
            data.jdkMajor = facts.jdkMajor;
            data.jdkVersion = facts.jdkVersion;
            data.profile = facts.profile;
            data.settingsJson = withK8s(facts.settingsJson, row);
            data.dtSeconds = facts.dtSeconds > 0d ? facts.dtSeconds : data.dtSeconds;
            ChunkFacts bound = facts;
            Extractors.extract(bound, data);
            data.derive = () -> Extractors.derive(bound, data);
        }
        V2Store.CommitResult result = store.commit(chunkId, data);
        if (result.wrote() && committed != null) {
            committed.fire(new WindowCommitted(chunkId, result.jvmId(), data.fromMs, data.toMs, result.late()));
        }
    }

    private ChunkIndexRow storedPayload(String chunkId) throws IOException {
        ChunkIndexRow row = index.get(chunkId);
        if (!stored(row)) {
            return null;
        }
        if (!Files.isRegularFile(recordingsDir.resolve(row.path()))) {
            return null;
        }
        return row;
    }

    private static boolean stored(ChunkIndexRow row) {
        return row != null
                && ChunkIndexRow.STATUS_STORED.equals(row.status())
                && row.path() != null
                && !row.path().isBlank();
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static String withK8s(String settings, ChunkIndexRow row) {
        try {
            JsonNode tree = JSON.readTree(settings == null ? "{}" : settings);
            if (!(tree instanceof ObjectNode object)) {
                return settings;
            }
            ObjectNode k8s = object.with("k8s");
            k8s.put("namespace", row.namespace());
            k8s.put("pod", row.podName());
            k8s.put("container", row.containerName());
            return JSON.writeValueAsString(object);
        } catch (IOException error) {
            return settings;
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}

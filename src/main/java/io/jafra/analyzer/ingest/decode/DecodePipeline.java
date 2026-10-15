package io.jafra.analyzer.ingest.decode;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.jafra.analyzer.retention.RetentionEngine;
import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;

/** Subscribes to chunk commit and backfills anything the manifest has not marked ok. */
@ApplicationScoped
@Startup
public class DecodePipeline {
    private static final Logger LOG = Logger.getLogger(DecodePipeline.class);

    private final ChunkDecodeService decoder;
    private final ExecutorService executor;
    private final ScheduledExecutorService compactor;
    private final Duration compactInterval;
    private final Duration compactMinAge;
    private final RetentionEngine retention;
    private final Duration retentionInterval;

    @Inject
    public DecodePipeline(
            ChunkStore store,
            @ConfigProperty(name = "jafra.storage.root", defaultValue = "/var/lib/jafra/analyzer") String root,
            @ConfigProperty(name = "jafra.decode.threads", defaultValue = "4") int threads,
            @ConfigProperty(name = "jafra.compact.interval", defaultValue = "10m") String compactInterval,
            @ConfigProperty(name = "jafra.compact.min-age", defaultValue = "1h") String compactMinAge,
            @ConfigProperty(name = "jafra.retention.mode", defaultValue = "delete-after-48h") String retentionMode,
            @ConfigProperty(name = "jafra.retention.raw-ttl", defaultValue = "48h") String rawTtl,
            @ConfigProperty(name = "jafra.retention.clear-parquet-cache", defaultValue = "false") boolean clearCache,
            @ConfigProperty(name = "jafra.retention.sweep-interval", defaultValue = "10m") String sweepInterval) throws SQLException {
        Path v2 = Path.of(root).resolve("v2");
        IngestCatalog catalog = new IngestCatalog(v2);
        this.decoder = new ChunkDecodeService(store, catalog, v2);
        this.compactInterval = parseDuration(compactInterval);
        this.compactMinAge = parseDuration(compactMinAge);
        this.retentionInterval = parseDuration(sweepInterval);
        this.retention = new RetentionEngine(store, catalog, v2, retentionMode, parseDuration(rawTtl), clearCache, retentionInterval);
        int pool = Math.max(1, Math.min(threads, Runtime.getRuntime().availableProcessors()));
        this.executor = Executors.newFixedThreadPool(pool, runnable -> {
            Thread thread = new Thread(runnable, "jafra-decode");
            thread.setDaemon(true);
            return thread;
        });
        this.compactor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jafra-compact");
            thread.setDaemon(true);
            return thread;
        });
        store.onCommit(this::onCommit);
    }

    void onStart(@Observes StartupEvent event) {
        executor.submit(this::backfillQuietly);
        long intervalMs = Math.max(1L, compactInterval.toMillis());
        compactor.scheduleWithFixedDelay(this::compactQuietly, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        long sweepMs = Math.max(1L, retentionInterval.toMillis());
        compactor.scheduleWithFixedDelay(this::sweepQuietly, sweepMs, sweepMs, TimeUnit.MILLISECONDS);
    }

    public RetentionEngine retention() {
        return retention;
    }

    /** Decode any committed chunk the manifest has not marked ok. A repeat call is a no-op. */
    public void ensureDecoded() {
        decoder.backfill();
    }

    void onCommit(ChunkMetadata metadata) {
        executor.submit(() -> decoder.decode(metadata));
    }

    private void backfillQuietly() {
        try {
            decoder.backfill();
        } catch (RuntimeException error) {
            LOG.warn("decode backfill failed", error);
        }
    }

    private void sweepQuietly() {
        try {
            retention.sweep(Instant.now());
        } catch (Exception error) {
            LOG.warn("retention sweep failed", error);
        }
    }

    private void compactQuietly() {
        try {
            decoder.compact(Instant.now(), compactMinAge);
        } catch (RuntimeException error) {
            LOG.warn("parquet compaction failed", error);
        }
    }

    static Duration parseDuration(String text) {
        String value = text == null ? "" : text.trim().toLowerCase();
        if (value.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
        }
        if (value.endsWith("h")) {
            return Duration.ofHours(Long.parseLong(value.substring(0, value.length() - 1)));
        }
        if (value.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
        }
        if (value.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
        }
        return Duration.parse(value);
    }
}

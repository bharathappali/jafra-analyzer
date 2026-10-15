package io.jafra.analyzer.ingest.decode;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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

    @Inject
    public DecodePipeline(
            ChunkStore store,
            @ConfigProperty(name = "jafra.storage.root", defaultValue = "/var/lib/jafra/analyzer") String root,
            @ConfigProperty(name = "jafra.decode.threads", defaultValue = "4") int threads) throws SQLException {
        Path v2 = Path.of(root).resolve("v2");
        IngestCatalog catalog = new IngestCatalog(v2);
        this.decoder = new ChunkDecodeService(store, catalog, v2);
        int pool = Math.max(1, Math.min(threads, Runtime.getRuntime().availableProcessors()));
        this.executor = Executors.newFixedThreadPool(pool, runnable -> {
            Thread thread = new Thread(runnable, "jafra-decode");
            thread.setDaemon(true);
            return thread;
        });
        store.onCommit(this::onCommit);
    }

    void onStart(@Observes StartupEvent event) {
        executor.submit(this::backfillQuietly);
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
}

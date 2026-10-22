package io.jafra.analyzer.storage;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;

/**
 * Legacy migration runs after the process is up. Reads stay unavailable until it finishes.
 * A failed attempt is logged and tried again after {@link #RETRY_DELAY_SECONDS}.
 */
@ApplicationScoped
public class StorageMigration {
    public static final long RETRY_DELAY_SECONDS = 60;

    private static final Logger LOG = Logger.getLogger(StorageMigration.class);

    private final ChunkStore store;
    private final ScheduledExecutorService executor;

    @Inject
    public StorageMigration(ChunkStore store) {
        this.store = store;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jafra-storage-migration");
            thread.setDaemon(true);
            return thread;
        });
    }

    void onStart(@Observes StartupEvent event) {
        if (store.ready()) {
            store.startSidecarRepair();
            return;
        }
        schedule(0);
    }

    void attempt() {
        try {
            store.runMigration(MigrationPause::await);
            store.startSidecarRepair();
        } catch (Exception error) {
            LOG.errorf(error, "storage migration failed; retrying in %d seconds", RETRY_DELAY_SECONDS);
            schedule(RETRY_DELAY_SECONDS);
        }
    }

    private void schedule(long delaySeconds) {
        executor.schedule(this::attempt, delaySeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}

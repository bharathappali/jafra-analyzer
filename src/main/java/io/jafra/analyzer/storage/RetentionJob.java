package io.jafra.analyzer.storage;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.scheduler.Scheduled;

/** Deletes stored chunks whose event window ended before the retention horizon. */
@ApplicationScoped
public class RetentionJob {
    private static final Logger LOG = Logger.getLogger(RetentionJob.class);

    private final ChunkStore store;
    private final Optional<Duration> retention;

    @Inject
    public RetentionJob(
            ChunkStore store,
            @ConfigProperty(name = "jafra.storage.retention") Optional<Duration> retention) {
        this.store = store;
        this.retention = retention;
    }

    @Scheduled(every = "15m")
    void sweep() {
        retention.ifPresent(this::purge);
    }

    public void purge(Duration retention) {
        if (retention == null) {
            return;
        }
        try {
            store.purgeExpired(retention);
        } catch (IOException error) {
            LOG.warnf(error, "retention sweep failed");
        }
    }
}

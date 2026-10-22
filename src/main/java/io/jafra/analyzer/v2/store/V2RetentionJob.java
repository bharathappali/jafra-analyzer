package io.jafra.analyzer.v2.store;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.jafra.analyzer.storage.ChunkStore;
import io.quarkus.scheduler.Scheduled;

/** Deletes v2 rows older than the same horizon as chunk retention. */
@ApplicationScoped
public class V2RetentionJob {
    private static final Logger LOG = Logger.getLogger(V2RetentionJob.class);

    private final ChunkStore chunks;
    private final V2Store store;
    private final Optional<Duration> retention;

    @Inject
    public V2RetentionJob(
            ChunkStore chunks,
            V2Store store,
            @ConfigProperty(name = "jafra.storage.retention") Optional<Duration> retention) {
        this.chunks = chunks;
        this.store = store;
        this.retention = retention;
    }

    @Scheduled(every = "15m")
    void sweep() {
        retention.ifPresent(this::purge);
    }

    public void purge(Duration retention) {
        if (!chunks.ready()) {
            LOG.debugf("skipping v2 retention until storage is ready");
            return;
        }
        if (retention == null) {
            return;
        }
        long horizon = Instant.now().minus(retention).toEpochMilli();
        try {
            store.purgeOlderThan(horizon);
        } catch (IOException error) {
            LOG.warnf(error, "v2 retention sweep failed");
        }
    }
}

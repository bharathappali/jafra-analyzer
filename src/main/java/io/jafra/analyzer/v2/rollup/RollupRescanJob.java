package io.jafra.analyzer.v2.rollup;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.jafra.analyzer.storage.ChunkStore;
import io.quarkus.scheduler.Scheduled;

/** Picks up stored chunks that have no v2 window yet. */
@ApplicationScoped
public class RollupRescanJob {
    private static final Logger LOG = Logger.getLogger(RollupRescanJob.class);

    private final ChunkStore store;
    private final RollupWriter writer;

    @Inject
    public RollupRescanJob(ChunkStore store, RollupWriter writer) {
        this.store = store;
        this.writer = writer;
    }

    @Scheduled(every = "{jafra.v2.rollup-rescan:1m}")
    void rescan() {
        if (!store.ready()) {
            LOG.debugf("skipping rollup rescan until storage is ready");
            return;
        }
        writer.rescan();
    }
}

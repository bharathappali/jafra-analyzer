package io.jafra.analyzer.index;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.jafra.analyzer.storage.ChunkStore;
import io.quarkus.scheduler.Scheduled;

/** Picks up stored chunks whose event window was never decoded. */
@ApplicationScoped
public class DecodeRescanJob {
    private static final Logger LOG = Logger.getLogger(DecodeRescanJob.class);

    private final ChunkStore store;
    private final EventRangeDecoder decoder;

    @Inject
    public DecodeRescanJob(ChunkStore store, EventRangeDecoder decoder) {
        this.store = store;
        this.decoder = decoder;
    }

    @Scheduled(every = "{jafra.index.decode-rescan:1m}")
    void rescan() {
        if (!store.ready()) {
            LOG.debugf("skipping decode rescan until storage is ready");
            return;
        }
        decoder.rescan();
    }
}

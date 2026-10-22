package io.jafra.analyzer.index;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.scheduler.Scheduled;

/** Picks up stored chunks whose event window was never decoded. */
@ApplicationScoped
public class DecodeRescanJob {
    private final EventRangeDecoder decoder;

    @Inject
    public DecodeRescanJob(EventRangeDecoder decoder) {
        this.decoder = decoder;
    }

    @Scheduled(every = "{jafra.index.decode-rescan:1m}")
    void rescan() {
        decoder.rescan();
    }
}

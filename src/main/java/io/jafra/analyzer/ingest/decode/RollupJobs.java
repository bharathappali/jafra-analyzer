package io.jafra.analyzer.ingest.decode;

import java.nio.file.Path;
import java.util.function.Consumer;

import org.jboss.logging.Logger;

/** Rollups are rows in rollup_pending. A failed refresh stays queued and the drain continues. */
public final class RollupJobs {
    private static final Logger LOG = Logger.getLogger(RollupJobs.class);
    /** Test hook. A throw leaves that row queued. */
    public static volatile Consumer<IngestCatalog.RollupJob> beforeRefresh;

    private volatile boolean paused;

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean paused() {
        return paused;
    }

    public int drain(IngestCatalog catalog, Path v2Root) throws Exception {
        if (paused) {
            return 0;
        }
        int ran = 0;
        for (IngestCatalog.RollupJob job : catalog.pendingRollups()) {
            Consumer<IngestCatalog.RollupJob> hook = beforeRefresh;
            try {
                if (hook != null) {
                    hook.accept(job);
                }
                RollupWriter.refresh(catalog.connection(), v2Root, job.namespace(), job.pod(), job.container(), job.instanceId(),
                        job.tStart(), job.tEnd());
                catalog.deleteRollup(job);
                ran++;
            } catch (Exception error) {
                LOG.warnf(error, "rollup refresh failed for %s", job.instanceId());
            }
        }
        return ran;
    }
}

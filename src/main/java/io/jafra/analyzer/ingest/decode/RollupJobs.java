package io.jafra.analyzer.ingest.decode;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.jboss.logging.Logger;

/** Rollups are rows in rollup_pending. Refresh runs on its own connection and deletes a row only after success. */
public final class RollupJobs {
    static final long MINUTE_NS = 60_000_000_000L;
    private static final Logger LOG = Logger.getLogger(RollupJobs.class);
    /** Test hook. A throw leaves that row queued. */
    public static volatile Consumer<IngestCatalog.RollupJob> beforeRefresh;

    private volatile boolean paused;
    /** Skip refresh while this many chunks are still queued or in flight. */
    private volatile int pauseBacklog = 64;

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean paused() {
        return paused;
    }

    public void setPauseBacklog(int pauseBacklog) {
        this.pauseBacklog = Math.max(0, pauseBacklog);
    }

    public int drain(IngestCatalog catalog, Path v2Root) throws Exception {
        if (paused || catalog.backlog() > pauseBacklog) {
            return 0;
        }
        List<Merged> merged = merge(catalog.pendingRollups());
        if (merged.isEmpty()) {
            return 0;
        }
        int ran = 0;
        try (Connection connection = DuckConnections.open("jdbc:duckdb:")) {
            for (Merged range : merged) {
                Consumer<IngestCatalog.RollupJob> hook = beforeRefresh;
                try {
                    if (hook != null && !range.jobs.isEmpty()) {
                        hook.accept(range.jobs.get(0));
                    }
                    RollupWriter.refresh(connection, v2Root, range.namespace, range.pod, range.container, range.instanceId,
                            range.tStart, range.tEnd);
                    catalog.deleteRollups(range.jobs);
                    ran++;
                } catch (Exception error) {
                    LOG.warnf(error, "rollup refresh failed for %s", range.instanceId);
                }
            }
        }
        return ran;
    }

    /** Contiguous minute ranges per JVM. Adjacent minutes become one refresh. */
    static List<Merged> merge(List<IngestCatalog.RollupJob> jobs) {
        Map<String, List<IngestCatalog.RollupJob>> grouped = new LinkedHashMap<>();
        for (IngestCatalog.RollupJob job : jobs) {
            String key = job.namespace() + "\0" + job.pod() + "\0" + job.container() + "\0" + job.instanceId();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(job);
        }
        List<Merged> merged = new ArrayList<>();
        for (List<IngestCatalog.RollupJob> group : grouped.values()) {
            group.sort(Comparator.comparingLong(IngestCatalog.RollupJob::tStart).thenComparingLong(IngestCatalog.RollupJob::tEnd));
            Merged current = null;
            for (IngestCatalog.RollupJob job : group) {
                long start = floor(job.tStart());
                long last = job.tEnd() > job.tStart() ? job.tEnd() - 1 : job.tStart();
                long end = floor(last) + MINUTE_NS;
                if (current != null && start <= current.tEnd) {
                    current.tEnd = Math.max(current.tEnd, end);
                    current.jobs.add(job);
                    continue;
                }
                current = new Merged(job.namespace(), job.pod(), job.container(), job.instanceId(), start, end, new ArrayList<>());
                current.jobs.add(job);
                merged.add(current);
            }
        }
        return merged;
    }

    private static long floor(long t) {
        return Math.floorDiv(t, MINUTE_NS) * MINUTE_NS;
    }

    static final class Merged {
        final String namespace;
        final String pod;
        final String container;
        final String instanceId;
        final long tStart;
        long tEnd;
        final List<IngestCatalog.RollupJob> jobs;

        Merged(String namespace, String pod, String container, String instanceId, long tStart, long tEnd,
                List<IngestCatalog.RollupJob> jobs) {
            this.namespace = namespace;
            this.pod = pod;
            this.container = container;
            this.instanceId = instanceId;
            this.tStart = tStart;
            this.tEnd = tEnd;
            this.jobs = jobs;
        }
    }
}

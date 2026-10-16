package io.jafra.analyzer.ingest.decode;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Rollups run after a chunk is marked done, so a slow rebuild cannot block the next chunk. */
public final class RollupJobs {
    private final Queue<Job> pending = new ConcurrentLinkedQueue<>();
    private volatile boolean paused;

    public void submit(String namespace, String pod, String container, String instanceId, long tStart, long tEnd) {
        pending.add(new Job(namespace, pod, container, instanceId, tStart, tEnd));
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean paused() {
        return paused;
    }

    public int drain(Connection connection, Path v2Root) throws Exception {
        if (paused) {
            return 0;
        }
        int ran = 0;
        Job job;
        while ((job = pending.poll()) != null) {
            RollupWriter.refresh(connection, v2Root, job.namespace(), job.pod(), job.container(), job.instanceId(), job.tStart(),
                    job.tEnd());
            ran++;
        }
        return ran;
    }

    private record Job(String namespace, String pod, String container, String instanceId, long tStart, long tEnd) {}
}

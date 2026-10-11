package io.jafra.analyzer.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import io.jafra.analyzer.recordings.RecordingCatalog;
import io.jafra.analyzer.recordings.WorkloadIndex;

@Path("/api/v1/workload")
@Produces(MediaType.APPLICATION_JSON)
public class WorkloadResource {
    @Inject
    RecordingWindowSelection selection;

    @Inject
    RecordingCatalog catalog;

    @Inject
    WorkloadIndex workloadIndex;

    @GET
    public Object workload(
            @QueryParam("namespace") String namespace,
            @QueryParam("pod") String pod,
            @QueryParam("container") String container) {
        if (!WorkloadIndex.blank(namespace) && !WorkloadIndex.blank(pod) && !WorkloadIndex.blank(container)) {
            RecordingWindowSelection.validateWorkloadParams(namespace, pod, container);
            RecordingCatalog.WorkloadRecordings workload = selection.requireWorkload(namespace, pod, container);
            RecordingCatalog.WorkloadKey key = new RecordingCatalog.WorkloadKey(namespace, pod, container);
            WorkloadIndex.WorkloadSnapshot stats = workloadIndex.snapshot(key);
            int recordingCount = workload.recordings().size();
            int chunkCount = stats == null ? 0 : stats.chunkCount();
            String start = stats == null ? null : stats.start();
            String end = stats == null ? null : stats.end();
            String jvmVersion = stats == null ? null : stats.jvmVersion();
            return new WorkloadIndex.WorkloadDetailDocument(
                    namespace,
                    pod,
                    container,
                    recordingCount,
                    chunkCount,
                    start,
                    end,
                    jvmVersion,
                    workload.recordings());
        }
        return workloadIndex.discover(namespace, pod, container);
    }
}

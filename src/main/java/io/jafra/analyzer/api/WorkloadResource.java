package io.jafra.analyzer.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import io.jafra.analyzer.recordings.RecordingCatalog;

@Path("/api/v1/workload")
@Produces(MediaType.APPLICATION_JSON)
public class WorkloadResource {
    @Inject
    RecordingWindowSelection selection;

    @GET
    public RecordingCatalog.WorkloadRecordings workload(
            @QueryParam("namespace") String namespace,
            @QueryParam("pod") String pod,
            @QueryParam("container") String container) {
        RecordingWindowSelection.validateWorkloadParams(namespace, pod, container);
        return selection.requireWorkload(namespace, pod, container);
    }
}

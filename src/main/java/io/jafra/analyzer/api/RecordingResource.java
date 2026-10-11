package io.jafra.analyzer.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import io.jafra.analyzer.recordings.RecordingCatalog;

@ApplicationScoped
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
public class RecordingResource {
    @Inject
    RecordingCatalog catalog;

    @GET
    @Path("recordings")
    public RecordingCatalog.RecordingListResponse list(
            @QueryParam("namespace") String namespace,
            @QueryParam("pod") String pod,
            @QueryParam("container") String container) {
        return catalog.list(namespace, pod, container);
    }
}

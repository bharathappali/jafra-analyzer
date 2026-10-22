package io.jafra.analyzer.v2.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import io.jafra.analyzer.v2.surface.SurfaceService;
import io.smallrye.common.annotation.Blocking;

@Path("/v2/workloads")
@Produces(MediaType.APPLICATION_JSON)
public class WorkloadResource {
    private final SurfaceService surface;

    @Inject
    public WorkloadResource(SurfaceService surface) {
        this.surface = surface;
    }

    @GET
    @Blocking
    public SurfaceService.WorkloadList list(@QueryParam("cluster") String cluster) {
        return surface.workloads(cluster);
    }
}

package io.jafra.analyzer.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.jafra.analyzer.ingest.IngestRegistry;

@Path("/api/v1/status")
@Produces(MediaType.APPLICATION_JSON)
public class StatusResource {
    @Inject
    IngestRegistry registry;

    @GET
    public IngestRegistry.StatusSnapshot status() {
        return registry.status();
    }
}

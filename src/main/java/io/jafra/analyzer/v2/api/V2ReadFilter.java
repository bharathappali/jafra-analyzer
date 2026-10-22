package io.jafra.analyzer.v2.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import io.jafra.analyzer.storage.ChunkStore;

/** v2 reads answer 503 until storage migration has finished. */
@ApplicationScoped
public class V2ReadFilter {
    private final ChunkStore store;

    @Inject
    public V2ReadFilter(ChunkStore store) {
        this.store = store;
    }

    @ServerRequestFilter
    public Response migration(ContainerRequestContext request) {
        if (store.ready()) {
            return null;
        }
        String path = request.getUriInfo().getPath();
        if (path == null || !(path.startsWith("/v2/") || path.startsWith("v2/"))) {
            return null;
        }
        return Response.status(503)
                .header("Retry-After", "30")
                .type(MediaType.APPLICATION_JSON)
                .entity(new V2Error("not_ready", "storage migration in progress", "retry shortly", java.util.List.of()))
                .build();
    }
}

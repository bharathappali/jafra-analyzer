package io.jafra.analyzer.api;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import io.jafra.analyzer.storage.ChunkStore;

/** HTTP reads answer 503 until storage migration has finished. */
@ApplicationScoped
public class StorageReadFilter {
    private final ChunkStore store;

    @Inject
    public StorageReadFilter(ChunkStore store) {
        this.store = store;
    }

    @ServerRequestFilter
    public Response migration(ContainerRequestContext request) {
        if (store.ready()) {
            return null;
        }
        String path = request.getUriInfo().getPath();
        if (path == null || !(path.startsWith("/api/") || path.startsWith("api/"))) {
            return null;
        }
        return Response.status(503)
                .header("Retry-After", "30")
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("status", "not_ready", "error", "storage migration in progress"))
                .build();
    }
}

package io.jafra.analyzer.v2.stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;

import org.jboss.resteasy.reactive.server.ServerResponseFilter;

@ApplicationScoped
public class StreamBufferFilter {
    @ServerResponseFilter
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String path = request.getUriInfo().getPath();
        if (path != null && path.contains("stream")) {
            response.getHeaders().putSingle("X-Accel-Buffering", "no");
        }
    }
}

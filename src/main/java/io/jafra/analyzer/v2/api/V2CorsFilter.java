package io.jafra.analyzer.v2.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

/** Browser UI on another origin calls /v2 and the SSE stream. */
@ApplicationScoped
public class V2CorsFilter {
    static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    static final String EXPOSE_HEADERS = "Access-Control-Expose-Headers";
    static final String ALLOW_METHODS = "Access-Control-Allow-Methods";
    static final String ALLOW_HEADERS = "Access-Control-Allow-Headers";

    @ServerRequestFilter(preMatching = true, priority = Priorities.HEADER_DECORATOR)
    public Response preflight(ContainerRequestContext request) {
        if (!"OPTIONS".equalsIgnoreCase(request.getMethod()) || !v2(request)) {
            return null;
        }
        return Response.noContent()
                .header(ALLOW_ORIGIN, "*")
                .header(EXPOSE_HEADERS, "Retry-After")
                .header(ALLOW_METHODS, "GET, POST, OPTIONS")
                .header(ALLOW_HEADERS, "Content-Type")
                .build();
    }

    @ServerResponseFilter
    public void cors(ContainerRequestContext request, ContainerResponseContext response) {
        if (!v2(request)) {
            return;
        }
        response.getHeaders().putSingle(ALLOW_ORIGIN, "*");
        response.getHeaders().putSingle(EXPOSE_HEADERS, "Retry-After");
    }

    static boolean v2(ContainerRequestContext request) {
        String path = request.getUriInfo().getPath();
        return path != null && (path.startsWith("/v2/") || path.startsWith("v2/") || "v2".equals(path) || "/v2".equals(path));
    }
}

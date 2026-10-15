package io.jafra.analyzer.api;

import java.io.IOException;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/** Marks every /api/v1 response deprecated and names the /v2 successor. */
@Provider
public class V1DeprecationFilter implements ContainerResponseFilter {
    static final String DEPRECATION = "Thu, 15 Oct 2026 00:00:00 GMT";

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) throws IOException {
        String path = request.getUriInfo().getPath();
        if (path == null) {
            return;
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        if (!normalized.startsWith("/api/v1")) {
            return;
        }
        response.getHeaders().putSingle("Deprecation", DEPRECATION);
        response.getHeaders().putSingle("Link", "<" + successor(normalized) + ">; rel=\"successor-version\"");
    }

    static String successor(String path) {
        if (path.startsWith("/api/v1/analytics/daily_stats")) {
            return "/v2/overview";
        }
        if (path.startsWith("/api/v1/analytics")) {
            return "/v2/series";
        }
        if (path.startsWith("/api/v1/aggregations/samples") || path.startsWith("/api/v1/aggregations/allocations")) {
            return "/v2/top";
        }
        if (path.startsWith("/api/v1/aggregations/trends")) {
            return "/v2/series";
        }
        if (path.startsWith("/api/v1/aggregations")) {
            return "/v2/events";
        }
        if (path.startsWith("/api/v1/stacks")) {
            return "/v2/stacks";
        }
        return "/v2";
    }
}

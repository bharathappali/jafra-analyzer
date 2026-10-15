package io.jafra.analyzer.api.v2;

import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/** ETag for /v2 reads from the catalog watermark and the retention file. */
@Provider
public class V2EtagFilter implements ContainerResponseFilter {
    private final Path v2;

    @Inject
    public V2EtagFilter(@ConfigProperty(name = "jafra.storage.root", defaultValue = "/var/lib/jafra/analyzer") String root) {
        this.v2 = Path.of(root).resolve("v2");
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String path = request.getUriInfo().getPath();
        if (path == null || !(path.startsWith("/v2") || path.startsWith("v2/")) || !"GET".equals(request.getMethod())) {
            return;
        }
        String tag = "\"" + stamp("catalog.duckdb") + "-" + stamp("retention.json") + "\"";
        response.getHeaders().putSingle("ETag", tag);
        String match = request.getHeaderString("If-None-Match");
        if (tag.equals(match)) {
            response.setStatus(304);
            response.setEntity(null);
        }
    }

    private long stamp(String name) {
        Path file = v2.resolve(name);
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
        } catch (java.io.IOException error) {
            return 0L;
        }
    }
}

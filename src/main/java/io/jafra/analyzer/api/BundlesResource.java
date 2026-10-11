package io.jafra.analyzer.api;

import java.io.IOException;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import io.jafra.analyzer.recordings.JfrBundleService;
import io.smallrye.common.annotation.Blocking;

@Path("/api/v1/bundles")
@Produces(MediaType.APPLICATION_JSON)
public class BundlesResource {
    @Inject
    RecordingWindowSelection selection;

    @Inject
    JfrBundleService bundles;

    @GET
    @Blocking
    public JfrBundleService.BundleCatalogDocument catalog() {
        return bundles.catalog();
    }

    @GET
    @Path("{id}")
    @Blocking
    public JfrBundleService.BundleDocument bundle(
            @PathParam("id") String id,
            @QueryParam("namespace") String namespace,
            @QueryParam("pod") String pod,
            @QueryParam("container") String container,
            @QueryParam("recording") String recording,
            @QueryParam("limit") Integer limit,
            @QueryParam("minDuration") String minDuration,
            @QueryParam("flavor") String flavor,
            @QueryParam("names") String flagNames,
            @QueryParam("last") String last,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("before") String before,
            @QueryParam("after") String after) {
        JfrBundleService.BundleParams params = new JfrBundleService.BundleParams(limit, minDuration, flavor, flagNames);
        return selection.withSelection(
                namespace,
                pod,
                container,
                recording,
                last,
                from,
                to,
                before,
                after,
                selected -> {
                    try {
                        return bundles.bundle(selected, id, params);
                    } catch (IllegalArgumentException error) {
                        throw RecordingWindowSelection.badRequest(error.getMessage());
                    } catch (IOException error) {
                        throw RecordingWindowSelection.unavailable(selected, error.getMessage());
                    }
                });
    }
}

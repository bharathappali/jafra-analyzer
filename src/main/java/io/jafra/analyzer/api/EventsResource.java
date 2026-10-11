package io.jafra.analyzer.api;

import java.io.IOException;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import io.jafra.analyzer.recordings.JfrCollectService;
import io.smallrye.common.annotation.Blocking;

@Path("/api/v1/events")
@Produces(MediaType.APPLICATION_JSON)
public class EventsResource {
    @Inject
    RecordingWindowSelection selection;

    @Inject
    JfrCollectService collect;

    @GET
    @Blocking
    public JfrCollectService.EventsDocument events(
            @QueryParam("namespace") String namespace,
            @QueryParam("pod") String pod,
            @QueryParam("container") String container,
            @QueryParam("recording") String recording,
            @QueryParam("types") String types,
            @QueryParam("view") String view,
            @QueryParam("field") String field,
            @QueryParam("minDuration") String minDuration,
            @QueryParam("limit") Integer limit,
            @QueryParam("last") String last,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("before") String before,
            @QueryParam("after") String after) {
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
                        return collect.events(selected, types, view, field, minDuration, limit);
                    } catch (IllegalArgumentException error) {
                        throw RecordingWindowSelection.badRequest(error.getMessage());
                    } catch (IOException error) {
                        throw RecordingWindowSelection.unavailable(selected, error.getMessage());
                    }
                });
    }
}

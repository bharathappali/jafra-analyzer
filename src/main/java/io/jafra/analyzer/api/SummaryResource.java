package io.jafra.analyzer.api;

import java.io.IOException;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import io.jafra.analyzer.recordings.JfrEventSummarizer;
import io.smallrye.common.annotation.Blocking;

@Path("/api/v1/summary")
@Produces(MediaType.APPLICATION_JSON)
public class SummaryResource {
    @Inject
    RecordingWindowSelection selection;

    @Inject
    JfrEventSummarizer summarizer;

    @GET
    @Blocking
    public JfrEventSummarizer.EventSummaryDocument summary(
            @QueryParam("namespace") String namespace,
            @QueryParam("pod") String pod,
            @QueryParam("container") String container,
            @QueryParam("recording") String recording,
            @QueryParam("filter") String filter,
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
                        return summarizer.summarize(selected, filter);
                    } catch (IOException error) {
                        throw RecordingWindowSelection.unavailable(selected, error.getMessage());
                    }
                });
    }
}

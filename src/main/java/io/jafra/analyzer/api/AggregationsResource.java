package io.jafra.analyzer.api;

import java.sql.SQLException;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import io.jafra.analyzer.recordings.JfrAggregationService;
import io.jafra.analyzer.recordings.V1DuckReadService;
import io.smallrye.common.annotation.Blocking;

@Path("/api/v1/aggregations")
@Produces(MediaType.APPLICATION_JSON)
public class AggregationsResource {
    @Inject
    RecordingWindowSelection selection;

    @Inject
    V1DuckReadService aggregations;

    @GET
    @Path("{kind}")
    @Blocking
    public JfrAggregationService.AggregationDocument aggregate(
            @PathParam("kind") String kind,
            @QueryParam("namespace") String namespace,
            @QueryParam("pod") String pod,
            @QueryParam("container") String container,
            @QueryParam("recording") String recording,
            @QueryParam("limit") Integer limit,
            @QueryParam("minDuration") String minDuration,
            @QueryParam("flavor") String flavor,
            @QueryParam("stacks") String stacks,
            @QueryParam("last") String last,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("before") String before,
            @QueryParam("after") String after) {
        return selection.withTimeSeries(
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
                        return aggregations.aggregate(
                                selected, kind, limit, minDuration, flavor, AnalyticsResource.stackIds(stacks));
                    } catch (IllegalArgumentException error) {
                        throw RecordingWindowSelection.badRequest(error.getMessage());
                    } catch (SQLException error) {
                        throw RecordingWindowSelection.unavailable(selected, error.getMessage());
                    }
                });
    }
}

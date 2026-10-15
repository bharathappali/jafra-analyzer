package io.jafra.analyzer.api;

import java.sql.SQLException;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import io.jafra.analyzer.recordings.V1DuckReadService;
import io.jafra.analyzer.recordings.analytics.AnalyticsModels;
import io.smallrye.common.annotation.Blocking;

@Path("/api/v1/analytics")
@Produces(MediaType.APPLICATION_JSON)
public class AnalyticsResource {
    @Inject
    RecordingWindowSelection selection;

    @Inject
    V1DuckReadService analytics;

    @GET
    @Path("timeseries")
    @Blocking
    public AnalyticsModels.AnalyticsDocument timeseries(
            @QueryParam("namespace") String namespace,
            @QueryParam("pod") String pod,
            @QueryParam("container") String container,
            @QueryParam("recording") String recording,
            @QueryParam("top") Integer top,
            @QueryParam("bucketSeconds") Long bucketSeconds,
            @QueryParam("subsystem") String subsystem,
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
                        return analytics.timeseries(selected, top, bucketSeconds, subsystem, stackIds(stacks));
                    } catch (IllegalArgumentException error) {
                        throw RecordingWindowSelection.badRequest(error.getMessage());
                    } catch (SQLException error) {
                        throw RecordingWindowSelection.unavailable(selected, error.getMessage());
                    }
                });
    }

    @GET
    @Path("daily_stats")
    @Blocking
    public AnalyticsModels.AnalyticsDocument dailyStats(
            @QueryParam("namespace") String namespace,
            @QueryParam("pod") String pod,
            @QueryParam("container") String container,
            @QueryParam("recording") String recording,
            @QueryParam("top") Integer top,
            @QueryParam("subsystem") String subsystem,
            @QueryParam("stacks") String stacks,
            @QueryParam("from") String from,
            @QueryParam("to") String to) {
        String last = (from != null && !from.isBlank() && to != null && !to.isBlank()) ? null : "24h";
        return selection.withTimeSeries(
                namespace,
                pod,
                container,
                recording,
                last,
                from,
                to,
                null,
                null,
                selected -> {
                    try {
                        return analytics.dailyStats(selected, top, subsystem, stackIds(stacks));
                    } catch (IllegalArgumentException error) {
                        throw RecordingWindowSelection.badRequest(error.getMessage());
                    } catch (SQLException error) {
                        throw RecordingWindowSelection.unavailable(selected, error.getMessage());
                    }
                });
    }

    static boolean stackIds(String stacks) {
        return stacks != null && "ids".equalsIgnoreCase(stacks.trim());
    }
}

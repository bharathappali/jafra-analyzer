package io.jafra.analyzer.retention;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import io.jafra.analyzer.api.RecordingWindowSelection;
import io.jafra.analyzer.ingest.decode.DecodePipeline;

/** Retention status, the tombstone feed, and the explicit delete actions. No auth in the MVP. */
@Path("/v2")
@Produces(MediaType.APPLICATION_JSON)
public class RetentionResource {
    private final DecodePipeline pipeline;

    @Inject
    public RetentionResource(DecodePipeline pipeline) {
        this.pipeline = pipeline;
    }

    @GET
    @Path("retention")
    public Map<String, Object> retention() {
        return pipeline.retention().status(Instant.now());
    }

    @GET
    @Path("retention/tombstones")
    public Map<String, Object> tombstones(@QueryParam("since") String since) {
        Instant from = since == null || since.isBlank() ? Instant.EPOCH : instant(since);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tombstones", pipeline.retention().tombstones(from));
        return body;
    }

    @DELETE
    @Path("instances/{ns}/{pod}/{container}/{instance}")
    public Map<String, Object> delete(
            @PathParam("ns") String ns,
            @PathParam("pod") String pod,
            @PathParam("container") String container,
            @PathParam("instance") String instance,
            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun) {
        return act("delete", ns, pod, container, instance, dryRun);
    }

    @POST
    @Path("instances/{ns}/{pod}/{container}/{instance}/clear-cache")
    public Map<String, Object> clearCache(
            @PathParam("ns") String ns,
            @PathParam("pod") String pod,
            @PathParam("container") String container,
            @PathParam("instance") String instance,
            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun) {
        return act("clear-cache", ns, pod, container, instance, dryRun);
    }

    @POST
    @Path("instances/{ns}/{pod}/{container}/{instance}/purge-raw")
    public Map<String, Object> purgeRaw(
            @PathParam("ns") String ns,
            @PathParam("pod") String pod,
            @PathParam("container") String container,
            @PathParam("instance") String instance,
            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun) {
        return act("purge-raw", ns, pod, container, instance, dryRun);
    }

    private Map<String, Object> act(String action, String ns, String pod, String container, String instance, boolean dryRun) {
        try {
            return pipeline.retention().apply(action, ns, pod, container, instance, dryRun, Instant.now());
        } catch (IllegalArgumentException error) {
            throw RecordingWindowSelection.badRequest(error.getMessage());
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw RecordingWindowSelection.badRequest(error.getMessage());
        }
    }

    private static Instant instant(String text) {
        String value = text.trim();
        try {
            if (value.chars().allMatch(Character::isDigit)) {
                return Instant.ofEpochMilli(Long.parseLong(value));
            }
            return Instant.parse(value);
        } catch (RuntimeException error) {
            throw RecordingWindowSelection.badRequest("since must be ISO-8601 or epoch milliseconds");
        }
    }
}

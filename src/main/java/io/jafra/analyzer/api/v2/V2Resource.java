package io.jafra.analyzer.api.v2;

import java.time.Instant;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import io.jafra.analyzer.api.RecordingWindowSelection;

/** Read API over the ingest Parquet tables. Heap generations are a later cut. */
@Path("/v2")
@Produces(MediaType.APPLICATION_JSON)
public class V2Resource {
    private final V2ReadService reads;

    @Inject
    public V2Resource(V2Database database) {
        this.reads = new V2ReadService(database.connection(), database.root());
    }

    @GET
    @Path("workloads")
    public Map<String, Object> workloads(@QueryParam("include_tombstoned") @DefaultValue("false") boolean includeTombstoned) {
        return call(() -> reads.workloads(Instant.now(), includeTombstoned));
    }

    @GET
    @Path("heap")
    public Map<String, Object> heap(
            @QueryParam("ns") String ns,
            @QueryParam("pod") String pod,
            @QueryParam("container") String container,
            @QueryParam("instance") String instance,
            @QueryParam("last") String last,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("before") String before,
            @QueryParam("after") String after,
            @QueryParam("format") @DefaultValue("compact") String format) {
        V2Window.Range range = window(last, from, to, before, after);
        return call(() -> reads.heap(identity(ns, pod, container, instance), range, compact(format)));
    }

    @GET
    @Path("instance")
    public Map<String, Object> instance(
            @QueryParam("ns") String ns,
            @QueryParam("pod") String pod,
            @QueryParam("container") String container,
            @QueryParam("instance") String instance) {
        return call(() -> reads.instance(identity(ns, pod, container, instance)));
    }

    @GET
    @Path("series")
    public Map<String, Object> series(
            @QueryParam("ns") String ns,
            @QueryParam("pod") String pod,
            @QueryParam("container") String container,
            @QueryParam("instance") String instance,
            @QueryParam("last") String last,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("before") String before,
            @QueryParam("after") String after,
            @QueryParam("step") @DefaultValue("auto") String step,
            @QueryParam("metrics") String metrics,
            @QueryParam("format") @DefaultValue("compact") String format) {
        V2Window.Range range = window(last, from, to, before, after);
        V2ReadService.Identity id = identity(ns, pod, container, instance);
        return call(() -> reads.series(id, range, step, metrics, compact(format)));
    }

    @GET
    @Path("top")
    public Map<String, Object> top(
            @QueryParam("ns") String ns,
            @QueryParam("pod") String pod,
            @QueryParam("container") String container,
            @QueryParam("instance") String instance,
            @QueryParam("last") String last,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("before") String before,
            @QueryParam("after") String after,
            @QueryParam("kind") @DefaultValue("cpu") String kind,
            @QueryParam("group") @DefaultValue("method") String group,
            @QueryParam("n") @DefaultValue("10") int limit,
            @QueryParam("compare_from") String compareFrom,
            @QueryParam("compare_to") String compareTo,
            @QueryParam("format") @DefaultValue("compact") String format) {
        V2Window.Range range = window(last, from, to, before, after);
        V2ReadService.Identity id = identity(ns, pod, container, instance);
        return call(() -> reads.top(id, range, kind, group, limit, compare(compareFrom, compareTo), compact(format)));
    }

    @GET
    @Path("events")
    public Map<String, Object> events(
            @QueryParam("ns") String ns,
            @QueryParam("pod") String pod,
            @QueryParam("container") String container,
            @QueryParam("instance") String instance,
            @QueryParam("last") String last,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("before") String before,
            @QueryParam("after") String after,
            @QueryParam("type") @DefaultValue("gc") String type,
            @QueryParam("min_ms") Double minMs,
            @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        V2Window.Range range = window(last, from, to, before, after);
        V2ReadService.Identity id = identity(ns, pod, container, instance);
        return call(() -> reads.events(id, range, type, minMs, cursor, limit));
    }

    @GET
    @Path("markers")
    public Map<String, Object> markers(
            @QueryParam("ns") String ns,
            @QueryParam("pod") String pod,
            @QueryParam("container") String container,
            @QueryParam("instance") String instance,
            @QueryParam("last") String last,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("before") String before,
            @QueryParam("after") String after,
            @QueryParam("kinds") String kinds,
            @QueryParam("min_severity") @DefaultValue("warn") String minSeverity) {
        V2Window.Range range = window(last, from, to, before, after);
        V2ReadService.Identity id = identity(ns, pod, container, instance);
        return call(() -> reads.markers(id, range, kinds, minSeverity));
    }

    @GET
    @Path("stacks/{stackId}")
    public Map<String, Object> stack(
            @QueryParam("ns") String ns,
            @QueryParam("pod") String pod,
            @QueryParam("container") String container,
            @QueryParam("instance") String instance,
            @PathParam("stackId") String stackId,
            @QueryParam("depth") @DefaultValue("12") int depth) {
        V2ReadService.Identity id = identity(ns, pod, container, instance);
        Map<String, Object> body = call(() -> reads.stack(id, stackId, depth));
        if (body == null) {
            throw new NotFoundException("stack " + stackId + " not found");
        }
        return body;
    }

    @GET
    @Path("compare")
    public Map<String, Object> compare(
            @QueryParam("ns") String ns,
            @QueryParam("pod") String pod,
            @QueryParam("container") String container,
            @QueryParam("instance") String instance,
            @QueryParam("last") String last,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("before") String before,
            @QueryParam("after") String after,
            @QueryParam("compare_from") String compareFrom,
            @QueryParam("compare_to") String compareTo,
            @QueryParam("metrics") String metrics,
            @QueryParam("format") @DefaultValue("compact") String format) {
        V2Window.Range range = window(last, from, to, before, after);
        V2ReadService.Identity id = identity(ns, pod, container, instance);
        V2Window.Range baseline = compare(compareFrom, compareTo);
        if (baseline == null) {
            throw RecordingWindowSelection.badRequest("compare_from and compare_to are required");
        }
        return call(() -> reads.compare(id, range, baseline, metrics, compact(format)));
    }

    @GET
    @Path("overview")
    public Map<String, Object> overview(
            @QueryParam("ns") String ns,
            @QueryParam("pod") String pod,
            @QueryParam("container") String container,
            @QueryParam("instance") String instance,
            @QueryParam("last") String last,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("before") String before,
            @QueryParam("after") String after,
            @QueryParam("format") @DefaultValue("compact") String format) {
        V2Window.Range range = window(last, from, to, before, after);
        V2ReadService.Identity id = identity(ns, pod, container, instance);
        return call(() -> reads.overview(id, range, compact(format)));
    }

    private V2ReadService.Identity identity(String ns, String pod, String container, String instance) {
        if (ns == null || ns.isBlank() || pod == null || pod.isBlank() || container == null || container.isBlank()) {
            throw RecordingWindowSelection.badRequest("ns, pod, and container are required");
        }
        if (instance == null || instance.isBlank()) {
            throw RecordingWindowSelection.badRequest("instance is required");
        }
        String resolved = instance;
        if ("latest".equals(instance)) {
            try {
                resolved = reads.latestInstance(ns, pod, container);
            } catch (Exception error) {
                throw RecordingWindowSelection.badRequest(error.getMessage());
            }
            if (resolved == null) {
                throw new NotFoundException("no JVM instance for " + ns + "/" + pod + "/" + container);
            }
        }
        return new V2ReadService.Identity(ns, pod, container, resolved);
    }

    private static V2Window.Range window(String last, String from, String to, String before, String after) {
        if ((before != null && !before.isBlank()) || (after != null && !after.isBlank())) {
            throw RecordingWindowSelection.badRequest("before and after are v1 windows");
        }
        try {
            V2Window.Range range = V2Window.parse(Instant.now(), last, from, to);
            if (!range.from().isBefore(range.to())) {
                throw new IllegalArgumentException("from must be before to");
            }
            return range;
        } catch (IllegalArgumentException error) {
            throw RecordingWindowSelection.badRequest(error.getMessage());
        }
    }

    private static V2Window.Range compare(String from, String to) {
        boolean hasFrom = from != null && !from.isBlank();
        boolean hasTo = to != null && !to.isBlank();
        if (!hasFrom && !hasTo) {
            return null;
        }
        if (hasFrom != hasTo) {
            throw RecordingWindowSelection.badRequest("compare_from and compare_to are a pair");
        }
        try {
            return V2Window.parse(Instant.now(), null, from, to);
        } catch (IllegalArgumentException error) {
            throw RecordingWindowSelection.badRequest(error.getMessage());
        }
    }

    private static boolean compact(String format) {
        if ("full".equals(format)) {
            return false;
        }
        if ("compact".equals(format) || format == null || format.isBlank()) {
            return true;
        }
        throw RecordingWindowSelection.badRequest("format must be compact or full");
    }

    private interface SqlCall<T> {
        T get() throws Exception;
    }

    private static <T> T call(SqlCall<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException error) {
            throw RecordingWindowSelection.badRequest(error.getMessage());
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw RecordingWindowSelection.badRequest(error.getMessage());
        }
    }
}

package io.jafra.analyzer.v2.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;

import org.jboss.resteasy.reactive.RestPath;

import io.jafra.analyzer.v2.frames.EventService;
import io.jafra.analyzer.v2.frames.FrameService;
import io.jafra.analyzer.v2.query.QueryResponse;
import io.jafra.analyzer.v2.query.QueryService;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;
import io.jafra.analyzer.v2.surface.SurfaceService;
import io.jafra.analyzer.v2.incidents.IncidentService;
import io.jafra.analyzer.v2.stream.StreamHub;
import io.jafra.analyzer.v2.tools.ToolRegistry;
import io.jafra.analyzer.v2.trend.TrendService;
import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import io.smallrye.common.annotation.Blocking;

@Path("/v2/jvms/{namespace}/{pod}/{container}")
@Produces(MediaType.APPLICATION_JSON)
public class JvmResource {
    private final JvmResolver jvms;
    private final QueryService queries;
    private final SurfaceService surface;
    private final FrameService frames;
    private final EventService events;
    private final TrendService trends;
    private final IncidentService incidents;
    private final StreamHub streams;
    private final ToolRegistry tools;

    @Inject
    public JvmResource(
            JvmResolver jvms,
            QueryService queries,
            SurfaceService surface,
            FrameService frames,
            EventService events,
            TrendService trends,
            IncidentService incidents,
            StreamHub streams,
            ToolRegistry tools) {
        this.jvms = jvms;
        this.queries = queries;
        this.surface = surface;
        this.frames = frames;
        this.events = events;
        this.trends = trends;
        this.incidents = incidents;
        this.streams = streams;
        this.tools = tools;
    }

    @GET
    @Blocking
    @Path("/query")
    public QueryResponse query(
            @RestPath String namespace,
            @RestPath String pod,
            @RestPath String container,
            @QueryParam("cluster") String cluster,
            @QueryParam("start") String start,
            @QueryParam("metric") String metric,
            @QueryParam("stat") String stat,
            @QueryParam("by") String by,
            @QueryParam("step") String step,
            @QueryParam("where") String where,
            @QueryParam("top") Integer top,
            @QueryParam("last") String last,
            @QueryParam("from") String from,
            @QueryParam("to") String to) {
        JvmRow jvm = jvms.resolve(namespace, pod, container, cluster, start);
        return queries.query(jvm, metric, stat, by, step, where, top, last, from, to);
    }

    @GET
    @Blocking
    @Path("/report")
    public SurfaceService.Report report(
            @RestPath String namespace,
            @RestPath String pod,
            @RestPath String container,
            @QueryParam("cluster") String cluster,
            @QueryParam("start") String start,
            @QueryParam("last") String last,
            @QueryParam("from") String from,
            @QueryParam("to") String to) {
        return surface.report(jvms.resolve(namespace, pod, container, cluster, start), last, from, to);
    }

    @GET
    @Blocking
    @Path("/timeline")
    public SurfaceService.Timeline timeline(
            @RestPath String namespace,
            @RestPath String pod,
            @RestPath String container,
            @QueryParam("cluster") String cluster,
            @QueryParam("start") String start,
            @QueryParam("last") String last,
            @QueryParam("from") String from,
            @QueryParam("to") String to) {
        return surface.timeline(jvms.resolve(namespace, pod, container, cluster, start), last, from, to);
    }

    @GET
    @Blocking
    @Path("/charts")
    public SurfaceService.ChartList charts(
            @RestPath String namespace,
            @RestPath String pod,
            @RestPath String container,
            @QueryParam("cluster") String cluster,
            @QueryParam("start") String start) {
        return surface.charts(jvms.resolve(namespace, pod, container, cluster, start));
    }

    @GET
    @Blocking
    @Path("/charts/{chartId}")
    public SurfaceService.ChartData chart(
            @RestPath String namespace,
            @RestPath String pod,
            @RestPath String container,
            @RestPath String chartId,
            @QueryParam("cluster") String cluster,
            @QueryParam("start") String start,
            @QueryParam("view") String view,
            @QueryParam("since") String since,
            @QueryParam("last") String last,
            @QueryParam("from") String from,
            @QueryParam("to") String to) {
        return surface.chart(
                jvms.resolve(namespace, pod, container, cluster, start), chartId, view, since, last, from, to);
    }

    @GET
    @Blocking
    @Path("/flame/{group}")
    public FrameService.Flame flame(
            @RestPath String namespace,
            @RestPath String pod,
            @RestPath String container,
            @RestPath String group,
            @QueryParam("cluster") String cluster,
            @QueryParam("start") String start,
            @QueryParam("kind") String kind,
            @QueryParam("thread") String thread,
            @QueryParam("state") String state,
            @QueryParam("handler") String handler,
            @QueryParam("last") String last,
            @QueryParam("from") String from,
            @QueryParam("to") String to) {
        return frames.flame(
                jvms.resolve(namespace, pod, container, cluster, start), group, kind, thread, state, handler, last, from, to);
    }

    @GET
    @Blocking
    @Path("/frames")
    public FrameService.TopFrames frames(
            @RestPath String namespace,
            @RestPath String pod,
            @RestPath String container,
            @QueryParam("cluster") String cluster,
            @QueryParam("start") String start,
            @QueryParam("kind") String kind,
            @QueryParam("thread") String thread,
            @QueryParam("state") String state,
            @QueryParam("endpoint") String endpoint,
            @QueryParam("focus") String focus,
            @QueryParam("top") Integer top,
            @QueryParam("last") String last,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("vs") String vs) {
        return frames.top(
                jvms.resolve(namespace, pod, container, cluster, start),
                kind, thread, state, endpoint, focus, top, last, from, to, vs);
    }

    @GET
    @Blocking
    @Path("/events")
    public EventService.EventList events(
            @RestPath String namespace,
            @RestPath String pod,
            @RestPath String container,
            @QueryParam("cluster") String cluster,
            @QueryParam("start") String start,
            @QueryParam("type") String type,
            @QueryParam("sort") String sort,
            @QueryParam("limit") Integer limit,
            @QueryParam("where") String where,
            @QueryParam("last") String last,
            @QueryParam("from") String from,
            @QueryParam("to") String to) {
        return events.list(jvms.resolve(namespace, pod, container, cluster, start), type, sort, limit, where, last, from, to);
    }

    @GET
    @Blocking
    @Path("/trend")
    public TrendService.Trend trend(
            @RestPath String namespace,
            @RestPath String pod,
            @RestPath String container,
            @QueryParam("cluster") String cluster,
            @QueryParam("start") String start,
            @QueryParam("metric") String metric,
            @QueryParam("last") String last,
            @QueryParam("from") String from,
            @QueryParam("to") String to) {
        return trends.trend(jvms.resolve(namespace, pod, container, cluster, start), metric, last, from, to);
    }

    @GET
    @Blocking
    @Path("/incidents")
    public com.fasterxml.jackson.databind.node.ObjectNode incidents(
            @RestPath String namespace,
            @RestPath String pod,
            @RestPath String container,
            @QueryParam("cluster") String cluster,
            @QueryParam("start") String start,
            @QueryParam("level") String level,
            @QueryParam("status") String status,
            @QueryParam("group") String group,
            @QueryParam("last") String last,
            @QueryParam("from") String from,
            @QueryParam("to") String to) {
        return incidents.list(
                jvms.resolve(namespace, pod, container, cluster, start), level, status, group, last, from, to);
    }

    @GET
    @Blocking
    @Path("/incidents/{id}")
    public com.fasterxml.jackson.databind.node.ObjectNode incident(
            @RestPath String namespace,
            @RestPath String pod,
            @RestPath String container,
            @RestPath String id,
            @QueryParam("cluster") String cluster,
            @QueryParam("start") String start) {
        return incidents.get(jvms.resolve(namespace, pod, container, cluster, start), id);
    }

    @GET
    @Path("/stream")
    @Produces(jakarta.ws.rs.core.MediaType.SERVER_SENT_EVENTS)
    public Multi<OutboundSseEvent> stream(
            @RestPath String namespace,
            @RestPath String pod,
            @RestPath String container,
            @QueryParam("cluster") String cluster,
            @QueryParam("start") String start,
            @jakarta.ws.rs.core.Context Sse sse,
            @jakarta.ws.rs.core.Context io.vertx.core.http.HttpServerResponse http) {
        http.putHeader("X-Accel-Buffering", "no");
        JvmRow jvm = jvms.resolve(namespace, pod, container, cluster, start);
        return Multi.createFrom().emitter(emitter -> {
            StreamHub.Subscription subscription = streams.subscribe(jvm, emitter, sse);
            emitter.onTermination(() -> streams.unsubscribe(subscription));
        });
    }

    @GET
    @Blocking
    @Path("/tools/{tool}")
    public Object tool(
            @RestPath String namespace,
            @RestPath String pod,
            @RestPath String container,
            @RestPath String tool,
            @QueryParam("cluster") String cluster,
            @QueryParam("start") String start,
            @Context UriInfo uri) {
        return tools.run(jvms.resolve(namespace, pod, container, cluster, start), tool, ToolRegistry.copy(uri.getQueryParameters()));
    }
}

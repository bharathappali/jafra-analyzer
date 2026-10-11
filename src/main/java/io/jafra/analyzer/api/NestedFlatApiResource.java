package io.jafra.analyzer.api;

import java.net.URI;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

import org.jboss.resteasy.reactive.RestQuery;

@ApplicationScoped
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
public class NestedFlatApiResource {

    @GET
    @Path("namespaces/{namespace}/pods/{pod}/containers/{container}")
    public void workload(
            @PathParam("namespace") String namespace,
            @PathParam("pod") String pod,
            @PathParam("container") String container) {
        throw redirect("/api/v1/workload", namespace, pod, container, null, null, null, null, null, null, null);
    }

    @GET
    @Path("namespaces/{namespace}/pods/{pod}/containers/{container}/report")
    public void workloadReport(
            @PathParam("namespace") String namespace,
            @PathParam("pod") String pod,
            @PathParam("container") String container,
            @RestQuery String recording,
            @RestQuery String filter,
            @RestQuery String last,
            @RestQuery String from,
            @RestQuery String to,
            @RestQuery String before,
            @RestQuery String after) {
        throw redirect(
                "/api/v1/report",
                namespace,
                pod,
                container,
                recording,
                filter,
                last,
                from,
                to,
                before,
                after);
    }

    @GET
    @Path("namespaces/{namespace}/pods/{pod}/containers/{container}/summary")
    public void workloadSummary(
            @PathParam("namespace") String namespace,
            @PathParam("pod") String pod,
            @PathParam("container") String container,
            @RestQuery String recording,
            @RestQuery String filter,
            @RestQuery String last,
            @RestQuery String from,
            @RestQuery String to,
            @RestQuery String before,
            @RestQuery String after) {
        throw redirect(
                "/api/v1/summary",
                namespace,
                pod,
                container,
                recording,
                filter,
                last,
                from,
                to,
                before,
                after);
    }

    @GET
    @Path("namespaces/{namespace}/pods/{pod}/containers/{container}/recordings/{filename}/report")
    public void recordingReport(
            @PathParam("namespace") String namespace,
            @PathParam("pod") String pod,
            @PathParam("container") String container,
            @PathParam("filename") String filename,
            @RestQuery String filter) {
        throw redirect(
                "/api/v1/report", namespace, pod, container, filename, filter, null, null, null, null, null);
    }

    @GET
    @Path("namespaces/{namespace}/pods/{pod}/containers/{container}/recordings/{filename}/summary")
    public void recordingSummary(
            @PathParam("namespace") String namespace,
            @PathParam("pod") String pod,
            @PathParam("container") String container,
            @PathParam("filename") String filename,
            @RestQuery String filter) {
        throw redirect(
                "/api/v1/summary", namespace, pod, container, filename, filter, null, null, null, null, null);
    }

    private static WebApplicationException redirect(
            String targetPath,
            String namespace,
            String pod,
            String container,
            String recording,
            String filter,
            String last,
            String from,
            String to,
            String before,
            String after) {
        UriBuilder builder = UriBuilder.fromPath(targetPath)
                .queryParam("namespace", namespace)
                .queryParam("pod", pod)
                .queryParam("container", container);
        if (recording != null && !recording.isBlank()) {
            builder.queryParam("recording", recording);
        }
        addQuery(builder, "filter", filter);
        addQuery(builder, "last", last);
        addQuery(builder, "from", from);
        addQuery(builder, "to", to);
        addQuery(builder, "before", before);
        addQuery(builder, "after", after);
        URI location = builder.build();
        String relativeLocation = location.getRawPath()
                + (location.getRawQuery() != null ? "?" + location.getRawQuery() : "");
        // Quarkus REST maps standard 3xx WebApplicationException responses to 404; 399 + Location is
        // the supported permanent-redirect pattern until upstream supports 308 on reactive REST.
        return new WebApplicationException(Response.status(399, "Permanent Redirect")
                .header("Location", relativeLocation)
                .build());
    }

    private static void addQuery(UriBuilder builder, String name, String value) {
        if (value != null && !value.isBlank()) {
            builder.queryParam(name, value);
        }
    }
}

package io.jafra.analyzer.v2.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.jafra.analyzer.v2.catalogue.Catalogue;

@Path("/v2/metrics")
@Produces(MediaType.APPLICATION_JSON)
public class MetricsResource {
    private final Catalogue catalogue;

    @Inject
    public MetricsResource(Catalogue catalogue) {
        this.catalogue = catalogue;
    }

    @GET
    public ObjectNode metrics(@QueryParam("group") String group, @QueryParam("jdk") Integer jdk) {
        return catalogue.metricsDocument(group, jdk);
    }
}

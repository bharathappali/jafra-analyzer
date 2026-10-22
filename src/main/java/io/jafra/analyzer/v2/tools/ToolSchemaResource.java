package io.jafra.analyzer.v2.tools;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.smallrye.common.annotation.Blocking;

@Path("/v2/tools")
@Produces(MediaType.APPLICATION_JSON)
public class ToolSchemaResource {
    private final ToolRegistry tools;

    @Inject
    public ToolSchemaResource(ToolRegistry tools) {
        this.tools = tools;
    }

    @GET
    @Blocking
    public ObjectNode list() {
        return tools.schemas();
    }
}

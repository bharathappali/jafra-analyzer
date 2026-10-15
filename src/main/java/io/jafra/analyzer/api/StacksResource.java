package io.jafra.analyzer.api;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import io.jafra.analyzer.recordings.StackIds;
import io.jafra.analyzer.recordings.V1DuckReadService;

@Path("/api/v1/stacks")
@Produces(MediaType.APPLICATION_JSON)
public class StacksResource {
    @Inject
    V1DuckReadService duck;

    @GET
    @Path("{id}")
    public StackView get(@PathParam("id") String id, @QueryParam("depth") @DefaultValue("16") int depth) {
        if (depth < 1) {
            throw RecordingWindowSelection.badRequest("depth must be at least 1");
        }
        StackIds.Identity identity = StackIds.get(id);
        if (identity != null) {
            List<FrameView> frames = new ArrayList<>();
            for (StackIds.Frame frame : identity.frames(depth)) {
                frames.add(new FrameView(frame.method(), frame.line(), frame.type()));
            }
            return new StackView(identity.stackId(), identity.depth(), identity.leaf(), identity.topAppFrame(), frames);
        }
        try {
            V1DuckReadService.ResolvedStack resolved = duck.stack(id, depth);
            if (resolved == null) {
                throw new NotFoundException("stack " + id + " not found");
            }
            List<FrameView> frames = new ArrayList<>();
            for (V1DuckReadService.ResolvedFrame frame : resolved.frames()) {
                frames.add(new FrameView(frame.method(), frame.line(), frame.type()));
            }
            return new StackView(resolved.stackId(), resolved.depth(), resolved.leaf(), resolved.topAppFrame(), frames);
        } catch (IllegalArgumentException error) {
            throw RecordingWindowSelection.badRequest(error.getMessage());
        } catch (SQLException error) {
            throw new NotFoundException("stack " + id + " not found");
        }
    }

    public record StackView(String stackId, int depth, String leaf, String topAppFrame, List<FrameView> frames) {}

    public record FrameView(String method, int line, String type) {}
}

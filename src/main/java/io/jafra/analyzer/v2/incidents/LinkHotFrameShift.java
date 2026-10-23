package io.jafra.analyzer.v2.incidents;

import jakarta.enterprise.context.ApplicationScoped;

/** Together rule LinkHotFrameShift. */
@ApplicationScoped
public class LinkHotFrameShift implements TogetherRule {
    @Override
    public void apply(TogetherContext ctx) {

        if (ctx.verdict("hot_frame_shift") != null) {
            StringBuilder ids = new StringBuilder();
            for (var row : ctx.openIncidents()) {
                if (!"hot_frame_shift".equals(row.type())) ids.append(row.id()).append(' ');
            }
            if (ids.length() > 0) ctx.prependTodo("hot_frame_shift", "explains " + ids.toString().trim());
        }
    }

    private static boolean bad(io.jafra.analyzer.v2.incidents.TogetherContext ctx, String type) {
        var verdict = ctx.verdict(type);
        return verdict != null && (verdict.state() == io.jafra.analyzer.v2.incidents.IncidentLifecycle.State.WARNING
                || verdict.state() == io.jafra.analyzer.v2.incidents.IncidentLifecycle.State.CRITICAL);
    }

    private static boolean open(io.jafra.analyzer.v2.incidents.TogetherContext ctx, String type) {
        for (var row : ctx.openIncidents()) if (type.equals(row.type())) return true;
        return false;
    }

    private static String id(io.jafra.analyzer.v2.incidents.TogetherContext ctx, String type) {
        for (var row : ctx.openIncidents()) if (type.equals(row.type()) && row.id() != null) return row.id();
        return type;
    }
}

package io.jafra.analyzer.v2.incidents;

import jakarta.enterprise.context.ApplicationScoped;

/** Together rule LinkEndpointSlowdown. */
@ApplicationScoped
public class LinkEndpointSlowdown implements TogetherRule {
    @Override
    public void apply(TogetherContext ctx) {

        String[] types = {"long_pause", "gc_overhead", "full_gc", "lock_contention", "single_hot_lock", "slow_peer", "cpu_throttling", "cpu_saturation"};
        for (String type : types) {
            if (bad(ctx, type) || open(ctx, type)) {
                var verdict = ctx.verdict(type);
                String todo = verdict != null && !verdict.todo().isEmpty() ? verdict.todo().get(0) : type;
                ctx.prependTodo("endpoint_slowdown", type + " is open (" + id(ctx, type) + "): " + todo);
                break;
            }
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

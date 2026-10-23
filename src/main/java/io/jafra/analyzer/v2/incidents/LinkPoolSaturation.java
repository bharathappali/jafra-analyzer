package io.jafra.analyzer.v2.incidents;

import jakarta.enterprise.context.ApplicationScoped;

/** Together rule LinkPoolSaturation. */
@ApplicationScoped
public class LinkPoolSaturation implements TogetherRule {
    @Override
    public void apply(TogetherContext ctx) {

        boolean linked = bad(ctx, "slow_requests") || bad(ctx, "endpoint_slowdown") || bad(ctx, "slow_peer") || bad(ctx, "lock_contention")
                || open(ctx, "slow_requests") || open(ctx, "endpoint_slowdown") || open(ctx, "slow_peer") || open(ctx, "lock_contention");
        if (linked) ctx.raiseCritical("pool_saturation", null, "linked to a slow request, endpoint, peer, or lock");
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

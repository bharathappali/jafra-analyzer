package io.jafra.analyzer.v2.incidents;

import jakarta.enterprise.context.ApplicationScoped;

/** Together rule LinkTtspSpike. */
@ApplicationScoped
public class LinkTtspSpike implements TogetherRule {
    @Override
    public void apply(TogetherContext ctx) {

        if (bad(ctx, "ttsp_spike") || open(ctx, "ttsp_spike")) {
            ctx.appendReason("ttsp_spike", "linked " + id(ctx, "cpu_throttling"));
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

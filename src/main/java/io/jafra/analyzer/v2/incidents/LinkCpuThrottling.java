package io.jafra.analyzer.v2.incidents;

import jakarta.enterprise.context.ApplicationScoped;

/** Together rule LinkCpuThrottling. */
@ApplicationScoped
public class LinkCpuThrottling implements TogetherRule {
    @Override
    public void apply(TogetherContext ctx) {

        if (bad(ctx, "cpu_throttling") || open(ctx, "cpu_throttling")) {
            String id = id(ctx, "cpu_throttling");
            String line = "CPU throttling is open (" + id + ", θ see cpu_throttling): fix throttling first; it stretches time-to-safepoint and GC pauses.";
            ctx.prependTodo("ttsp_spike", line);
            ctx.prependTodo("long_pause", line);
            ctx.appendReason("cpu_throttling", "linked " + id);
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

package io.jafra.analyzer.v2.incidents;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.IncidentFlags;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

/** Share of calls slower than the MethodTrace threshold. JDK 25 only. */
@ApplicationScoped
public class SlowRequestsDetector implements Detector {
    private final double warningPct;
    private final double criticalPct;
    private final int minSlow;
    private final double defaultThresholdMs;

    @Inject
    public SlowRequestsDetector(DetectorBands bands) {
        this(bands.slowRequests().warningPct(), bands.slowRequests().criticalPct(), bands.slowRequests().minSlow(),
                bands.slowRequests().defaultThresholdMs());
    }

    public SlowRequestsDetector(double warningPct, double criticalPct, int minSlow, double defaultThresholdMs) {
        this.warningPct = warningPct;
        this.criticalPct = criticalPct;
        this.minSlow = minSlow;
        this.defaultThresholdMs = defaultThresholdMs;
    }

    @Override
    public String type() { return "slow_requests"; }
    @Override
    public String group() { return "endpoint"; }
    @Override
    public IncidentLifecycle.Kind kind() { return IncidentLifecycle.Kind.RATE_LEVEL; }
    @Override
    public String family() { return "A"; }
    @Override
    public Set<Integer> jdks() { return Set.of(25); }

    @Override
    public WindowVerdict evaluate(WindowInput in) {
        WindowVerdict gate = DetectorSupport.jdkGate(this, in);
        if (gate != null) return gate;
        if (in.window() == null || in.window().total(DetectorMetrics.ENDPOINT_CALLS).isEmpty()) {
            return WindowVerdict.unknown("no " + DetectorMetrics.ENDPOINT_CALLS + " row", Set.of());
        }
        double calls = in.window().total(DetectorMetrics.ENDPOINT_CALLS).map(RollupRow::sum).orElse(0d);
        List<RollupRow> slow = in.window().breakdown(DetectorMetrics.ENDPOINT_SLOW_COUNT, "handler");
        if (slow.isEmpty() || calls <= 0d) {
            return WindowVerdict.unknown("no " + DetectorMetrics.ENDPOINT_SLOW_COUNT + " row", Set.of());
        }
        RollupRow top = slow.get(0);
        double share = 100d * top.sum() / calls;
        State state = State.OK;
        if (top.sum() >= minSlow && share >= criticalPct) state = State.CRITICAL;
        else if (top.sum() >= minSlow && share >= warningPct) state = State.WARNING;
        double threshold = in.settings().argument("jafra.io/latency-threshold").map(Double::parseDouble).orElse(defaultThresholdMs);
        Set<String> flags = new LinkedHashSet<>();
        flags.add("lower_bound");
        String reason = DetectorSupport.band(HeapSizeAdvice.number(warningPct) + "% / " + HeapSizeAdvice.number(criticalPct) + "%")
                + "; threshold " + HeapSizeAdvice.number(threshold) + " ms";
        if (MethodTimingDelta.mode() == MethodTimingDelta.Mode.AUTO) {
            flags.add(IncidentFlags.methodtiming_mode_inferred.name());
            reason += "; inferred mode " + MethodTimingDelta.infer(List.of());
        }
        return Verdicts.of(state, share, "pct", 100d, new Band(warningPct, criticalPct), family(), reason,
                new Who("handler", top.key(), share, false), List.of(),
                List.of("look at unknown on the slow path"),
                HeapSizeAdvice.number(share) + "% of " + top.key() + " calls took longer than " + HeapSizeAdvice.number(threshold) + " ms.",
                "Slow requests " + HeapSizeAdvice.number(share) + "%",
                List.of("jdk.MethodTrace", "jdk.MethodTiming"), flags, false);
    }
}

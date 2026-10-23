package io.jafra.analyzer.v2.incidents;

import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

/** Time to safepoint. The p99 term needs 50 safepoints and 60 windows. */
@ApplicationScoped
public class TtspSpikeDetector implements Detector {
    private final double warningMs;
    private final double criticalMs;
    private final double p99Multiple;
    private final long minSafepoints;
    private final int minWindows;

    @Inject
    public TtspSpikeDetector(DetectorBands bands) {
        this(bands.ttspSpike().warningMs(), bands.ttspSpike().criticalMs(), bands.ttspSpike().p99Multiple(),
                bands.ttspSpike().minSafepoints(), bands.ttspSpike().minWindows());
    }

    public TtspSpikeDetector(double warningMs, double criticalMs, double p99Multiple, long minSafepoints, int minWindows) {
        this.warningMs = warningMs;
        this.criticalMs = criticalMs;
        this.p99Multiple = p99Multiple;
        this.minSafepoints = minSafepoints;
        this.minWindows = minWindows;
    }

    @Override
    public String type() { return "ttsp_spike"; }
    @Override
    public String group() { return "safepoint"; }
    @Override
    public IncidentLifecycle.Kind kind() { return IncidentLifecycle.Kind.INSTANT; }
    @Override
    public String family() { return "A"; }
    @Override
    public Set<Integer> jdks() { return Set.of(17, 21, 25); }

    @Override
    public WindowVerdict evaluate(WindowInput in) {
        WindowVerdict gate = DetectorSupport.jdkGate(this, in);
        if (gate != null) return gate;
        if (in.window() == null || in.window().total(DetectorMetrics.SAFEPOINT_TTSP).isEmpty()) {
            return WindowVerdict.unknown("no " + DetectorMetrics.SAFEPOINT_TTSP + " row", Set.of());
        }
        double micros = in.window().total(DetectorMetrics.SAFEPOINT_TTSP).map(RollupRow::max).orElse(0d);
        double ms = micros / 1000d;
        Baseline baseline = in.baselines() == null ? null : in.baselines().of(DetectorMetrics.SAFEPOINT_TTSP, "", "safepoint", "max");
        double band = warningMs;
        String source = "floor " + HeapSizeAdvice.number(warningMs) + " ms";
        boolean p99 = baseline != null && baseline.p99() != null && baseline.events() >= minSafepoints && baseline.n() >= minWindows;
        if (p99) {
            band = Math.max(warningMs, p99Multiple * baseline.p99() / 1000d);
            source = HeapSizeAdvice.number(p99Multiple) + " × baseline p99";
        } else {
            source += "; p99 term dropped";
        }
        State state = ms >= criticalMs ? State.CRITICAL : ms >= band ? State.WARNING : State.OK;
        return Verdicts.of(state, ms, "ms", null, new Band(band, criticalMs), family(),
                DetectorSupport.band(source) + "; JFR does not record which thread was late",
                new Who("site", "unknown", null, false), java.util.List.of(),
                java.util.List.of("check CPU throttling first (it stretches time-to-safepoint)",
                        "then look for long loops without safepoint polls (counted loops over long ranges)"),
                "Threads took " + HeapSizeAdvice.number(ms) + " ms to reach a safepoint, so every thread waited.",
                "TTSP " + HeapSizeAdvice.number(ms) + " ms",
                java.util.List.of("jdk.SafepointBegin"), Set.of(), false);
    }
}

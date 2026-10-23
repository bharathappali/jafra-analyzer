package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

/** CPU throttling. Tau alone can be critical. */
@ApplicationScoped
public class CpuThrottlingDetector implements Detector {
    private final double warningPct;
    private final double criticalPct;
    private final double criticalSeconds;

    @Inject
    public CpuThrottlingDetector(DetectorBands bands) {
        this(bands.cpuThrottling().warningPct(), bands.cpuThrottling().criticalPct(), bands.cpuThrottling().criticalSeconds());
    }

    public CpuThrottlingDetector(double warningPct, double criticalPct, double criticalSeconds) {
        this.warningPct = warningPct;
        this.criticalPct = criticalPct;
        this.criticalSeconds = criticalSeconds;
    }

    @Override
    public String type() {
        return "cpu_throttling";
    }

    @Override
    public String group() {
        return "cpu";
    }

    @Override
    public IncidentLifecycle.Kind kind() {
        return IncidentLifecycle.Kind.RATE_LEVEL;
    }

    @Override
    public String family() {
        return "A";
    }

    @Override
    public Set<Integer> jdks() {
        return Set.of(17, 21, 25);
    }

    @Override
    public WindowVerdict evaluate(WindowInput in) {
        WindowVerdict gate = DetectorSupport.jdkGate(this, in);
        if (gate != null) {
            return gate;
        }
        if (in.window() == null || in.window().total(DetectorMetrics.CPU_THROTTLED_PCT).isEmpty()) {
            return WindowVerdict.unknown("too few CPU periods in this window", Set.of());
        }
        double theta = in.window().total(DetectorMetrics.CPU_THROTTLED_PCT).map(RollupRow::avg).orElse(0d);
        double tau = in.window().total(DetectorMetrics.CPU_THROTTLED_S_PER_S).map(RollupRow::avg).orElse(0d);
        State state = State.OK;
        if (theta >= criticalPct || tau >= criticalSeconds) {
            state = State.CRITICAL;
        } else if (theta >= warningPct) {
            state = State.WARNING;
        }
        double cores = in.window().total(DetectorMetrics.CPU_LIMIT_CORES).map(RollupRow::avg).orElse(0d);
        List<RollupRow> pools = in.window().breakdown(DetectorMetrics.CPU_THREAD_PCT, "pool");
        String pool = pools.isEmpty() ? "unknown" : pools.get(0).key();
        double share = pools.isEmpty() ? 0d : pools.get(0).sum();
        return Verdicts.of(
                state,
                theta,
                "pct",
                null,
                new Band(warningPct, criticalPct),
                family(),
                DetectorSupport.band(HeapSizeAdvice.number(warningPct) + "% / " + HeapSizeAdvice.number(criticalPct)
                        + "% or τ " + criticalSeconds + " s/s") + "; τ " + HeapSizeAdvice.number(tau),
                new Who("thread", pool, share, true),
                List.of(),
                List.of(
                        "raise the pod CPU limit from " + HeapSizeAdvice.number(cores) + " cores",
                        "cut CPU at unknown (0%)"),
                "The pod hit its CPU limit and was paused in " + HeapSizeAdvice.number(theta) + "% of CPU periods.",
                "CPU throttled " + HeapSizeAdvice.number(theta) + "%",
                List.of("jdk.ContainerCPUThrottling"),
                Set.of("estimate"),
                false);
    }
}

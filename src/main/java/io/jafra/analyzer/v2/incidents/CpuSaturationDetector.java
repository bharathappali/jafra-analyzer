package io.jafra.analyzer.v2.incidents;

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

/** Pod CPU near its limit. No CPU limit stays unknown. */
@ApplicationScoped
public class CpuSaturationDetector implements Detector {
    private final double warningPct;
    private final double criticalPct;
    private final double throttlePct;

    @Inject
    public CpuSaturationDetector(DetectorBands bands) {
        this(bands.cpuSaturation().warningPct(), bands.cpuSaturation().criticalPct(), bands.cpuSaturation().throttlePct());
    }

    public CpuSaturationDetector(double warningPct, double criticalPct, double throttlePct) {
        this.warningPct = warningPct;
        this.criticalPct = criticalPct;
        this.throttlePct = throttlePct;
    }

    @Override
    public String type() {
        return "cpu_saturation";
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
        if (!in.settings().hasCpuLimit()) {
            return WindowVerdict.unknown("skipped: no CPU limit", Set.of(IncidentFlags.no_cpu_limit.name()));
        }
        if (in.window() == null || in.window().total(DetectorMetrics.CPU_CONTAINER_PCT_LIMIT).isEmpty()) {
            return WindowVerdict.unknown("no " + DetectorMetrics.CPU_CONTAINER_PCT_LIMIT + " row", Set.of());
        }
        double value = in.window().total(DetectorMetrics.CPU_CONTAINER_PCT_LIMIT).map(RollupRow::avg).orElse(0d);
        double theta = in.window().total(DetectorMetrics.CPU_THROTTLED_PCT).map(RollupRow::avg).orElse(0d);
        State state = State.OK;
        if (value >= criticalPct && theta >= throttlePct) {
            state = State.CRITICAL;
        } else if (value >= warningPct) {
            state = State.WARNING;
        }
        return Verdicts.of(
                state,
                value,
                "pct",
                100d,
                new Band(warningPct, criticalPct),
                family(),
                DetectorSupport.band(HeapSizeAdvice.number(warningPct) + "% / " + HeapSizeAdvice.number(criticalPct) + "% and θ "
                        + HeapSizeAdvice.number(throttlePct) + "%"),
                new Who("thread", "unknown", null, true),
                List.of(),
                List.of("raise the CPU limit or cut CPU at unknown"),
                "The pod is using " + HeapSizeAdvice.number(value) + "% of its CPU limit.",
                "CPU " + HeapSizeAdvice.number(value) + "% of the limit",
                List.of("jdk.ContainerCPUUsage"),
                Set.of("estimate"),
                false);
    }
}

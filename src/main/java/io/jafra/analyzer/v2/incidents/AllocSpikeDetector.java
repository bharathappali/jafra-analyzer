package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.RobustZ;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

/** Allocation rate far above its own baseline. Warning only. */
@ApplicationScoped
public class AllocSpikeDetector implements Detector {
    private final double zMin;
    private final double multiple;

    @Inject
    public AllocSpikeDetector(DetectorBands bands) {
        this(bands.allocSpike().z(), bands.allocSpike().multiple());
    }

    public AllocSpikeDetector(double zMin, double multiple) {
        this.zMin = zMin;
        this.multiple = multiple;
    }

    @Override
    public String type() {
        return "alloc_spike";
    }

    @Override
    public String group() {
        return "alloc";
    }

    @Override
    public IncidentLifecycle.Kind kind() {
        return IncidentLifecycle.Kind.RATE_LEVEL;
    }

    @Override
    public String family() {
        return "D";
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
        if (in.window() == null || in.baselines() == null) {
            return WindowVerdict.unknown("no " + DetectorMetrics.ALLOC_RATE + " row", Set.of());
        }
        var row = in.window().total(DetectorMetrics.ALLOC_RATE);
        if (row.isEmpty() || row.get().count() <= 0) {
            return WindowVerdict.unknown("no " + DetectorMetrics.ALLOC_RATE + " row", Set.of());
        }
        double value = row.get().avg();
        Baseline baseline = in.baselines().of(DetectorMetrics.ALLOC_RATE, "", "alloc", "avg");
        int n = baseline == null ? 0 : baseline.n();
        if (n < RobustZ.WARMUP || baseline.median() == null || baseline.mad() == null) {
            return WindowVerdict.unknown("baseline warming up (" + n + "/12)", Set.of());
        }
        double z = RobustZ.z(value, baseline.median(), baseline.mad(), n);
        boolean hot = z >= zMin && value >= multiple * baseline.median();
        State state = hot ? State.WARNING : State.OK;
        Who who = AllocSites.allocatingClass(in);
        List<WhereFrame> where = AllocSites.where(in);
        String frame = where.isEmpty() ? "unknown" : where.get(0).frame();
        double share = where.isEmpty() ? 0d : where.get(0).sharePct();
        Set<String> flags = RobustZ.unproven(n) ? Set.of("estimate", "unproven_baseline") : Set.of("estimate");
        double times = baseline.median() <= 0d ? 0d : value / baseline.median();
        return Verdicts.of(
                state,
                value,
                "bytes_per_s",
                baseline.median(),
                new Band(multiple * baseline.median(), null),
                family(),
                DetectorSupport.band(HeapSizeAdvice.number(multiple) + "× median and z " + HeapSizeAdvice.number(zMin)),
                who,
                where,
                List.of(
                        "cut allocation at " + frame + " (" + HeapSizeAdvice.number(share) + "%)",
                        "allocation rate " + HeapSizeAdvice.bytes(value) + "/s vs normal " + HeapSizeAdvice.bytes(baseline.median()) + "/s"),
                "The app is allocating " + HeapSizeAdvice.number(times) + "× more memory than normal.",
                "Allocation " + HeapSizeAdvice.bytes(value) + "/s vs normal " + HeapSizeAdvice.bytes(baseline.median()) + "/s",
                List.of("jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB"),
                flags,
                false);
    }
}

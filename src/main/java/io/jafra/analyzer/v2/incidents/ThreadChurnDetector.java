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

/** Threads started far above the baseline, or an absolute flood. */
@ApplicationScoped
public class ThreadChurnDetector implements Detector {
    private final double warningPerS;
    private final double criticalPerS;
    private final double zMin;

    @Inject
    public ThreadChurnDetector(DetectorBands bands) {
        this(bands.threadChurn().warningPerS(), bands.threadChurn().criticalPerS(), bands.threadChurn().z());
    }

    public ThreadChurnDetector(double warningPerS, double criticalPerS, double zMin) {
        this.warningPerS = warningPerS;
        this.criticalPerS = criticalPerS;
        this.zMin = zMin;
    }

    @Override
    public String type() { return "thread_churn"; }
    @Override
    public String group() { return "threads"; }
    @Override
    public IncidentLifecycle.Kind kind() { return IncidentLifecycle.Kind.RATE_LEVEL; }
    @Override
    public String family() { return "B"; }
    @Override
    public Set<Integer> jdks() { return Set.of(17, 21, 25); }

    @Override
    public WindowVerdict evaluate(WindowInput in) {
        WindowVerdict gate = DetectorSupport.jdkGate(this, in);
        if (gate != null) return gate;
        if (in.window() == null || in.window().total(DetectorMetrics.THREADS_STARTED).isEmpty()) {
            return WindowVerdict.unknown("no " + DetectorMetrics.THREADS_STARTED + " row", Set.of());
        }
        double value = in.window().total(DetectorMetrics.THREADS_STARTED).map(RollupRow::avg).orElse(0d);
        Baseline baseline = in.baselines() == null ? null : in.baselines().of(DetectorMetrics.THREADS_STARTED, "", "threads", "avg");
        int n = baseline == null ? 0 : baseline.n();
        if (value < criticalPerS && (baseline == null || baseline.median() == null || n < RobustZ.WARMUP)) {
            return WindowVerdict.unknown("baseline warming up (" + n + "/12)", Set.of());
        }
        double z = baseline == null || baseline.median() == null || baseline.mad() == null ? 0d
                : RobustZ.z(value, baseline.median(), baseline.mad(), Math.max(n, RobustZ.WARMUP));
        State state = State.OK;
        if (value >= criticalPerS) state = State.CRITICAL;
        else if (value >= warningPerS && z >= zMin) state = State.WARNING;
        List<RollupRow> creators = in.window().breakdown(DetectorMetrics.THREADS_CREATED, "creator");
        String who = creators.isEmpty() ? "unknown" : creators.get(0).key();
        return Verdicts.of(state, value, "per_s", null, new Band(warningPerS, criticalPerS), family(),
                DetectorSupport.band(HeapSizeAdvice.number(warningPerS) + "/s / " + HeapSizeAdvice.number(criticalPerS) + "/s"),
                new Who("thread", who, null, false), List.of(),
                List.of("use a thread pool instead of creating threads at " + who),
                "The app creates " + HeapSizeAdvice.number(value) + " new threads per second.",
                "Thread starts " + HeapSizeAdvice.number(value) + "/s",
                List.of("jdk.ThreadStart"), Set.of(), false);
    }
}

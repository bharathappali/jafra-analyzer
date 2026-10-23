package io.jafra.analyzer.v2.incidents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorHistory;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.IncidentFlags;
import io.jafra.analyzer.v2.incidents.support.InternalInputs;
import io.jafra.analyzer.v2.incidents.support.RobustZ;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.rollup.Frame;
import io.jafra.analyzer.v2.store.V2Store.SampleRow;

/** Filtered lock wait. Writes I8 every window. */
@ApplicationScoped
public class LockContentionDetector implements Detector {
    private final double warning;
    private final double critical;
    private final double zMin;
    private final String formula;
    private final DetectorHistory history;
    private final List<String> idle;

    @Inject
    public LockContentionDetector(DetectorBands bands, DetectorHistory history) {
        this(bands.lockContention().warningThreadS(), bands.lockContention().criticalThreadS(), bands.lockContention().z(),
                bands.lockContention().waitFormula(), history, List.of(bands.lockContention().idleFrames().split(",")));
    }

    public LockContentionDetector(double warning, double critical, double zMin, String formula, DetectorHistory history, List<String> idle) {
        this.warning = warning;
        this.critical = critical;
        this.zMin = zMin;
        this.formula = formula == null ? "raw" : formula;
        this.history = history;
        this.idle = idle;
    }

    @Override
    public String type() {
        return "lock_contention";
    }

    @Override
    public String group() {
        return "locks";
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
        if (in.samples() == null) {
            return WindowVerdict.unknown("no lock samples", Set.of(IncidentFlags.lock_formula_unverified.name()));
        }
        double seconds = Math.max(0.001d, (in.toMs() - in.fromMs()) / 1000d);
        double waitMs = 0d;
        Map<String, Double> byLock = new LinkedHashMap<>();
        for (SampleRow sample : in.samples().rows("lock")) {
            if (idle(in, sample)) {
                continue;
            }
            double di = sample.weight();
            if ("corrected".equalsIgnoreCase(formula)) {
                di = Math.max(di, interval(in));
            }
            waitMs += di;
            String lock = sample.lockClass() == null ? "unknown" : sample.lockClass();
            byLock.merge(lock, di, Double::sum);
        }
        double value = (waitMs / 1000d) / seconds;
        if (history != null && in.jvm() != null) {
            history.record(in.jvm().jvmId(), InternalInputs.LOCK_WAIT, "", in.fromMs(), value);
        }
        Baseline baseline = in.baselines() == null
                ? new Baseline(0d, 0d, 0, 0, null, Set.of())
                : in.baselines().of(InternalInputs.LOCK_WAIT, "", "locks", "avg");
        int n = baseline.n();
        Double z = n < RobustZ.WARMUP || baseline.median() == null || baseline.mad() == null
                ? null
                : RobustZ.z(value, baseline.median(), baseline.mad(), n);
        State state = State.OK;
        if (value >= critical) {
            state = State.CRITICAL;
        } else if (value >= warning && z != null && z >= zMin) {
            state = State.WARNING;
        } else if (value >= warning && z == null) {
            return WindowVerdict.unknown("baseline warming up (" + n + "/12)", Set.of(IncidentFlags.lock_formula_unverified.name(), "estimate"));
        }
        String lock = "unknown";
        double best = -1d;
        for (Map.Entry<String, Double> entry : byLock.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                lock = entry.getKey();
            }
        }
        Set<String> flags = new java.util.LinkedHashSet<>();
        flags.add("estimate");
        flags.add(IncidentFlags.lock_formula_unverified.name());
        if (z != null && RobustZ.unproven(n)) {
            flags.add("unproven_baseline");
        }
        return Verdicts.of(
                state,
                value,
                "thread_s_per_s",
                null,
                new Band(warning, critical),
                family(),
                DetectorSupport.band(warning + " / " + critical + " thread-s/s") + "; formula " + formula,
                new Who("lock", lock, best <= 0d ? null : 100d * best / Math.max(waitMs, 1d), true),
                List.of(),
                List.of("shorten or split the critical section at unknown", "CPU samples inside the holder show why it is held long: unknown"),
                "Threads spent " + HeapSizeAdvice.number(value) + " s per second waiting for locks.",
                "Lock wait " + HeapSizeAdvice.number(value) + " thread-s/s (" + formula + ")",
                List.of("jdk.JavaMonitorEnter", "jdk.ThreadPark"),
                flags,
                false);
    }

    private boolean idle(WindowInput in, SampleRow sample) {
        List<Frame> stack = in.samples().stack(sample.stackId());
        return DetectorSupport.stackContains(stack, idle);
    }

    private double interval(WindowInput in) {
        String profile = in.settings().profile();
        if ("aggressive".equals(profile)) {
            return 0.1d;
        }
        if ("medium".equals(profile)) {
            return 1d;
        }
        return 10d;
    }
}

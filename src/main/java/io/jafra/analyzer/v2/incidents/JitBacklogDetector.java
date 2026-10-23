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

/** Compiler queue on JDK 25. Older JDKs are not_on_this_jdk. */
@ApplicationScoped
public class JitBacklogDetector implements Detector {
    private final double queueSize;

    @Inject
    public JitBacklogDetector(DetectorBands bands) {
        this(bands.jitBacklog().queueSize());
    }

    public JitBacklogDetector(double queueSize) {
        this.queueSize = queueSize;
    }

    @Override
    public String type() { return "jit_backlog"; }
    @Override
    public String group() { return "jit"; }
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
        if (in.window() == null || in.window().total(DetectorMetrics.JIT_QUEUE).isEmpty()) {
            return WindowVerdict.unknown("no " + DetectorMetrics.JIT_QUEUE + " row", Set.of());
        }
        double value = in.window().total(DetectorMetrics.JIT_QUEUE).map(RollupRow::max).orElse(0d);
        State state = value >= queueSize ? State.WARNING : State.OK;
        double cores = in.window().total(DetectorMetrics.CPU_LIMIT_CORES).map(RollupRow::avg).orElse(0d);
        return Verdicts.of(state, value, "count", null, new Band(queueSize, null), family(),
                DetectorSupport.band(HeapSizeAdvice.number(queueSize) + " methods queued"),
                new Who("thread", "C2 CompilerThread", null, false), List.of(),
                List.of("give the pod more CPU (limit " + HeapSizeAdvice.number(cores) + " cores)", "check CPU throttling"),
                HeapSizeAdvice.number(value) + " methods are waiting to be compiled, so code runs slower for longer.",
                "JIT queue " + HeapSizeAdvice.number(value),
                List.of("jdk.CompilerStatistics"), Set.of(), false);
    }
}

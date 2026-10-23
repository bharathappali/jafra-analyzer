package io.jafra.analyzer.v2.incidents;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

/** Share of time all threads are stopped for VM operations. */
@ApplicationScoped
public class SafepointOverheadDetector implements Detector {
    private final double warningPct;
    private final double criticalPct;

    @Inject
    public SafepointOverheadDetector(DetectorBands bands) {
        this(bands.safepointOverhead().warningPct(), bands.safepointOverhead().criticalPct());
    }

    public SafepointOverheadDetector(double warningPct, double criticalPct) {
        this.warningPct = warningPct;
        this.criticalPct = criticalPct;
    }

    @Override
    public String type() { return "safepoint_overhead"; }
    @Override
    public String group() { return "safepoint"; }
    @Override
    public IncidentLifecycle.Kind kind() { return IncidentLifecycle.Kind.RATE_LEVEL; }
    @Override
    public String family() { return "A"; }
    @Override
    public Set<Integer> jdks() { return Set.of(17, 21, 25); }

    @Override
    public WindowVerdict evaluate(WindowInput in) {
        WindowVerdict gate = DetectorSupport.jdkGate(this, in);
        if (gate != null) return gate;
        if (in.window() == null || in.window().total(DetectorMetrics.SAFEPOINT_TIME_PCT).isEmpty()) {
            return WindowVerdict.unknown("no " + DetectorMetrics.SAFEPOINT_TIME_PCT + " row", Set.of());
        }
        double value = in.window().total(DetectorMetrics.SAFEPOINT_TIME_PCT).map(RollupRow::avg).orElse(0d);
        State state = value >= criticalPct ? State.CRITICAL : value >= warningPct ? State.WARNING : State.OK;
        List<RollupRow> ops = in.window().breakdown(DetectorMetrics.SAFEPOINT_DURATION, "operation");
        String op = ops.isEmpty() ? "unknown" : ops.get(0).key();
        Set<String> flags = new LinkedHashSet<>();
        if ("low".equals(in.settings().profile())) flags.add("lower_bound");
        return Verdicts.of(state, value, "pct", 100d, new Band(warningPct, criticalPct), family(),
                DetectorSupport.band(HeapSizeAdvice.number(warningPct) + "% / " + HeapSizeAdvice.number(criticalPct) + "%"),
                new Who("site", op, null, false), List.of(),
                List.of(todo(op)),
                "All threads were stopped " + HeapSizeAdvice.number(value) + "% of the time for VM operations.",
                "Safepoints " + HeapSizeAdvice.number(value) + "%",
                List.of("jdk.SafepointBegin", "jdk.ExecuteVMOperation"), flags, false);
    }

    private static String todo(String operation) {
        if (operation.contains("Deoptimize")) return "see deopt_storm";
        if (operation.contains("Redefine") || operation.contains("Retransform")) return "an instrumenting agent redefined classes";
        if (operation.contains("G1") || operation.contains("GC")) return "safepoint time is GC";
        if (operation.contains("Handshake") || operation.contains("ThreadDump")) return "who is dumping threads";
        return "look at VM operation " + operation;
    }
}

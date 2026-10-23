package io.jafra.analyzer.v2.incidents;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorHistory;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.IncidentFlags;
import io.jafra.analyzer.v2.incidents.support.InternalInputs;
import io.jafra.analyzer.v2.incidents.support.RobustZ;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

/** A handler got slower than its own per-window baseline. JDK 25 only. */
@ApplicationScoped
public class EndpointSlowdownDetector implements Detector {
    private final double minCalls;
    private final double warningMultiple;
    private final double zMin;
    private final double criticalMaxMultiple;
    private final double criticalAvgMultiple;
    private final DetectorHistory history;

    @Inject
    public EndpointSlowdownDetector(DetectorBands bands, DetectorHistory history) {
        this(bands.endpointSlowdown().minCalls(), bands.endpointSlowdown().warningMultiple(), bands.endpointSlowdown().z(),
                bands.endpointSlowdown().criticalMaxMultiple(), bands.endpointSlowdown().criticalAvgMultiple(), history);
    }

    public EndpointSlowdownDetector(double minCalls, double warningMultiple, double zMin, double criticalMaxMultiple,
            double criticalAvgMultiple, DetectorHistory history) {
        this.minCalls = minCalls;
        this.warningMultiple = warningMultiple;
        this.zMin = zMin;
        this.criticalMaxMultiple = criticalMaxMultiple;
        this.criticalAvgMultiple = criticalAvgMultiple;
        this.history = history;
    }

    @Override
    public String type() { return "endpoint_slowdown"; }
    @Override
    public String group() { return "endpoint"; }
    @Override
    public IncidentLifecycle.Kind kind() { return IncidentLifecycle.Kind.RATE_LEVEL; }
    @Override
    public String family() { return "D"; }
    @Override
    public Set<Integer> jdks() { return Set.of(25); }

    @Override
    public WindowVerdict evaluate(WindowInput in) {
        WindowVerdict gate = DetectorSupport.jdkGate(this, in);
        if (gate != null) return gate;
        if (in.window() == null) return WindowVerdict.unknown("no " + DetectorMetrics.ENDPOINT_LATENCY + " row", Set.of());
        List<RollupRow> rows = in.window().breakdown(DetectorMetrics.ENDPOINT_LATENCY, "handler");
        if (rows.isEmpty()) return WindowVerdict.unknown("no " + DetectorMetrics.ENDPOINT_LATENCY + " row", Set.of());
        RollupRow row = rows.get(0);
        String handler = row.key();
        double calls = row.count();
        if (history != null) {
            history.record(in.jvm().jvmId(), InternalInputs.ENDPOINT_WINDOW, handler + ";field=calls", in.fromMs(), calls);
            history.record(in.jvm().jvmId(), InternalInputs.ENDPOINT_WINDOW, handler + ";field=avg_us", in.fromMs(), row.avg());
            history.record(in.jvm().jvmId(), InternalInputs.ENDPOINT_WINDOW, handler + ";field=max_us", in.fromMs(), row.max());
        }
        if (calls < minCalls) {
            return WindowVerdict.ok(row.avg(), "us", null, new Band(0d, null), family(), "fewer than " + HeapSizeAdvice.number(minCalls) + " calls", flags());
        }
        Baseline baseline = in.baselines() == null ? null
                : in.baselines().of(InternalInputs.ENDPOINT_WINDOW, handler + ";field=avg_us", "endpoint", "avg");
        int n = baseline == null ? 0 : baseline.n();
        if (baseline == null || baseline.median() == null || n < RobustZ.WARMUP) {
            return WindowVerdict.unknown("baseline warming up (" + n + "/12)", flags());
        }
        double z = RobustZ.z(row.avg(), baseline.median(), baseline.mad() == null ? 0d : baseline.mad(), n);
        MethodTimingDelta.Mode inferred = MethodTimingDelta.mode() == MethodTimingDelta.Mode.AUTO
                ? MethodTimingDelta.Mode.PER_PERIOD : MethodTimingDelta.mode();
        boolean critical = row.max() >= criticalMaxMultiple * baseline.median() && row.avg() >= criticalAvgMultiple * baseline.median();
        boolean warning = row.avg() >= warningMultiple * baseline.median() && z >= zMin;
        State state = critical ? State.CRITICAL : warning ? State.WARNING : State.OK;
        String reason = DetectorSupport.band(HeapSizeAdvice.number(warningMultiple) + "× / " + HeapSizeAdvice.number(criticalAvgMultiple) + "× median");
        if (MethodTimingDelta.mode() == MethodTimingDelta.Mode.AUTO) reason += "; inferred mode " + inferred;
        return Verdicts.of(state, row.avg(), "us", baseline.median(), new Band(warningMultiple * baseline.median(), criticalAvgMultiple * baseline.median()),
                family(), reason, new Who("handler", handler, null, false), List.of(),
                List.of("look at unknown on the slow path"),
                handler + " got " + HeapSizeAdvice.number(baseline.median() <= 0d ? 0d : row.avg() / baseline.median()) + "× slower than normal.",
                "Endpoint " + handler + " slowed down",
                List.of("jdk.MethodTiming"), flags(), false);
    }

    private static Set<String> flags() {
        Set<String> flags = new LinkedHashSet<>();
        if (MethodTimingDelta.mode() == MethodTimingDelta.Mode.AUTO) flags.add(IncidentFlags.methodtiming_mode_inferred.name());
        return flags;
    }
}

package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.IncidentFlags;
import io.jafra.analyzer.v2.incidents.support.RobustZ;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;
import io.jafra.analyzer.v2.store.V2Store.SampleRow;

/** Exception rate, or fillInStackTrace taking a share of CPU. */
@ApplicationScoped
public class ExceptionStormDetector implements Detector {
    private final double warningPerS;
    private final double zMin;
    private final double fillPct;
    private final int minSamples;

    @Inject
    public ExceptionStormDetector(DetectorBands bands) {
        this(bands.exceptionStorm().warningPerS(), bands.exceptionStorm().z(), bands.exceptionStorm().fillPct(), bands.exceptionStorm().minSamples());
    }

    public ExceptionStormDetector(double warningPerS, double zMin, double fillPct, int minSamples) {
        this.warningPerS = warningPerS;
        this.zMin = zMin;
        this.fillPct = fillPct;
        this.minSamples = minSamples;
    }

    @Override
    public String type() { return "exception_storm"; }
    @Override
    public String group() { return "exceptions"; }
    @Override
    public IncidentLifecycle.Kind kind() { return IncidentLifecycle.Kind.RATE_LEVEL; }
    @Override
    public String family() { return "D"; }
    @Override
    public Set<Integer> jdks() { return Set.of(17, 21, 25); }

    @Override
    public WindowVerdict evaluate(WindowInput in) {
        WindowVerdict gate = DetectorSupport.jdkGate(this, in);
        if (gate != null) return gate;
        if (in.window() == null || in.window().total(DetectorMetrics.EXCEPTIONS_THROWN).isEmpty()) {
            return WindowVerdict.unknown("no " + DetectorMetrics.EXCEPTIONS_THROWN + " row", Set.of());
        }
        double value = in.window().total(DetectorMetrics.EXCEPTIONS_THROWN).map(RollupRow::avg).orElse(0d);
        Baseline baseline = in.baselines() == null ? null : in.baselines().of(DetectorMetrics.EXCEPTIONS_THROWN, "", "exceptions", "avg");
        int n = baseline == null ? 0 : baseline.n();
        double fill = fillShare(in);
        boolean critical = fill >= fillPct && cpuSamples(in) >= minSamples;
        if (!critical && (baseline == null || baseline.median() == null || n < RobustZ.WARMUP)) {
            return WindowVerdict.unknown("baseline warming up (" + n + "/12)", Set.of("estimate"));
        }
        double z = baseline == null || baseline.median() == null || baseline.mad() == null ? 0d
                : RobustZ.z(value, baseline.median(), baseline.mad(), Math.max(n, RobustZ.WARMUP));
        State state = critical ? State.CRITICAL : value >= warningPerS && z >= zMin ? State.WARNING : State.OK;
        String reason = DetectorSupport.band(HeapSizeAdvice.number(warningPerS) + "/s or fillInStackTrace " + HeapSizeAdvice.number(fillPct) + "%");
        if (cpuSamples(in) < minSamples) reason += "; fillInStackTrace share unavailable";
        return Verdicts.of(state, value, "per_s", null, new Band(warningPerS, fillPct), family(), reason,
                new Who("class", "not available: the exception class breakdown is a later release (JavaExceptionThrow is off)", null, true),
                List.of(),
                List.of("stop using exceptions for control flow at unknown", "or reuse a preallocated exception without a stack trace"),
                "The app throws " + HeapSizeAdvice.number(value) + " exceptions per second.",
                "Exceptions " + HeapSizeAdvice.number(value) + "/s",
                List.of("jdk.JavaExceptionThrow", "jdk.ExecutionSample"), Set.of("estimate"), false);
    }

    private static int cpuSamples(WindowInput in) {
        return in.samples() == null ? 0 : in.samples().rows("cpu").size();
    }

    private static double fillShare(WindowInput in) {
        if (in.samples() == null) return 0d;
        List<SampleRow> rows = in.samples().rows("cpu");
        if (rows.size() < 1) return 0d;
        int hit = 0;
        for (SampleRow row : rows) {
            if (DetectorSupport.stackContains(in.samples().stack(row.stackId()), List.of("java.lang.Throwable.fillInStackTrace"))) hit++;
        }
        return 100d * hit / rows.size();
    }
}

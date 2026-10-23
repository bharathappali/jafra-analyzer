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

/** Classes still loading long after startup. */
@ApplicationScoped
public class ClassLoadBurstDetector implements Detector {
    private final double perS;
    private final double zMin;
    private final int minUptimeS;

    @Inject
    public ClassLoadBurstDetector(DetectorBands bands) {
        this(bands.classLoadBurst().perS(), bands.classLoadBurst().z(), bands.classLoadBurst().minUptimeS());
    }

    public ClassLoadBurstDetector(double perS, double zMin, int minUptimeS) {
        this.perS = perS;
        this.zMin = zMin;
        this.minUptimeS = minUptimeS;
    }

    @Override
    public String type() { return "class_load_burst"; }
    @Override
    public String group() { return "classes"; }
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
        if (in.jvm() == null || in.fromMs() - in.jvm().jvmStartMs() < minUptimeS * 1000L) {
            return WindowVerdict.ok(0d, "per_s", null, new Band(perS, null), family(), "ignored before " + minUptimeS + " s uptime", Set.of());
        }
        if (in.window() == null || in.window().total(DetectorMetrics.CLASSES_LOADED).isEmpty()) {
            return WindowVerdict.unknown("no " + DetectorMetrics.CLASSES_LOADED + " row", Set.of());
        }
        double value = in.window().total(DetectorMetrics.CLASSES_LOADED).map(RollupRow::avg).orElse(0d);
        Baseline baseline = in.baselines() == null ? null : in.baselines().of(DetectorMetrics.CLASSES_LOADED, "", "classes", "avg");
        int n = baseline == null ? 0 : baseline.n();
        if (baseline == null || baseline.median() == null || n < RobustZ.WARMUP) {
            return WindowVerdict.unknown("baseline warming up (" + n + "/12)", Set.of());
        }
        double z = RobustZ.z(value, baseline.median(), baseline.mad(), n);
        State state = value >= perS && z >= zMin ? State.WARNING : State.OK;
        return Verdicts.of(state, value, "per_s", null, new Band(perS, null), family(),
                DetectorSupport.band(HeapSizeAdvice.number(perS) + "/s") + "; coarse: loader stats every 60 s; class-load events are off",
                new Who("loader", "unknown", null, false), List.of(),
                List.of("look for runtime proxy, lambda or script generation in unknown"),
                "The app is still loading " + HeapSizeAdvice.number(value) + " classes per second long after startup.",
                "Class loads " + HeapSizeAdvice.number(value) + "/s",
                List.of("jdk.ClassLoadingStatistics"), Set.of(), false);
    }
}

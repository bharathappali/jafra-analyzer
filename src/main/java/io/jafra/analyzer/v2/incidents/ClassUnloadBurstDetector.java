package io.jafra.analyzer.v2.incidents;

import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

/** A burst of class unloading. Warning only. */
@ApplicationScoped
public class ClassUnloadBurstDetector implements Detector {
    private final double perS;

    @Inject
    public ClassUnloadBurstDetector(DetectorBands bands) {
        this(bands.classUnloadBurst().perS());
    }

    public ClassUnloadBurstDetector(double perS) {
        this.perS = perS;
    }

    @Override
    public String type() { return "class_unload_burst"; }
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
        if (in.window() == null || in.window().total(DetectorMetrics.CLASSES_UNLOADED).isEmpty()) {
            return WindowVerdict.unknown("no " + DetectorMetrics.CLASSES_UNLOADED + " row", Set.of());
        }
        double value = in.window().total(DetectorMetrics.CLASSES_UNLOADED).map(RollupRow::avg).orElse(0d);
        State state = value >= perS ? State.WARNING : State.OK;
        return Verdicts.of(state, value, "per_s", null, new Band(perS, null), family(),
                DetectorSupport.band(HeapSizeAdvice.number(perS) + "/s"),
                new Who("loader", "unknown", null, false), java.util.List.of(),
                java.util.List.of("usually a redeploy or generated classes being discarded"),
                "The JVM unloaded " + HeapSizeAdvice.number(value) + " classes per second.",
                "Class unloads " + HeapSizeAdvice.number(value) + "/s",
                java.util.List.of("jdk.ClassLoadingStatistics"), Set.of(), false);
    }
}

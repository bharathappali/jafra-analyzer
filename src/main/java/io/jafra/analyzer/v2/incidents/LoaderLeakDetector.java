package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorHistory;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.InternalInputs;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.trend.TrendService;

/** Class-loader count rising for 30 minutes. Warning only. */
@ApplicationScoped
public class LoaderLeakDetector implements Detector {
    private final int minPoints;
    private final DetectorHistory history;

    @Inject
    public LoaderLeakDetector(DetectorBands bands, DetectorHistory history) {
        this(bands.loaderLeak().minPoints(), history);
    }

    public LoaderLeakDetector(int minPoints, DetectorHistory history) {
        this.minPoints = minPoints;
        this.history = history;
    }

    @Override
    public String type() {
        return "loader_leak";
    }

    @Override
    public String group() {
        return "classes";
    }

    @Override
    public IncidentLifecycle.Kind kind() {
        return IncidentLifecycle.Kind.RATE_LEVEL;
    }

    @Override
    public String family() {
        return "C";
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
        if (history == null) {
            return WindowVerdict.unknown("not enough data yet (0/" + minPoints + ")", Set.of());
        }
        List<DetectorHistory.Point> points = history.points(
                in.jvm().jvmId(), InternalInputs.LOADER_COUNT, "", "avg", in.toMs() - 30L * 60_000L, in.toMs() + 1);
        if (points.size() < minPoints) {
            return WindowVerdict.unknown("not enough data yet (" + points.size() + "/" + minPoints + ")", Set.of());
        }
        long[] times = new long[points.size()];
        double[] values = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            times[i] = points.get(i).ts();
            values[i] = points.get(i).value();
        }
        TrendService.Analysis analysis = TrendService.analyze(times, values, null);
        if (!analysis.known()) {
            return WindowVerdict.unknown(analysis.reason(), Set.of());
        }
        boolean rising = analysis.result().significant() && analysis.result().slopePerHour() > 0d;
        double latest = points.get(points.size() - 1).value();
        State state = rising ? State.WARNING : State.OK;
        return Verdicts.of(
                state,
                latest,
                "count",
                null,
                new Band((double) minPoints, null),
                family(),
                DetectorSupport.band("loader count rising") + "; class-load events are off",
                new Who("loader", "unknown: " + HeapSizeAdvice.number(latest) + " loaders, 0 classes, 0 chunk bytes", null, false),
                List.of(),
                List.of("look for redeploys, scripting engines, or proxy/lambda generators creating new unknown loaders"),
                "New class loaders keep appearing and are never freed.",
                "Class loaders " + HeapSizeAdvice.number(latest),
                List.of("jdk.ClassLoaderStatistics"),
                Set.of(),
                false);
    }
}

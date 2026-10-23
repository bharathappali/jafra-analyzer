package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorHistory;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.trend.TrendService;

/** Live thread count keeps rising after warm-up. Warning only. */
@ApplicationScoped
public class ThreadLeakDetector implements Detector {
    private final double multiple;
    private final DetectorHistory history;

    @Inject
    public ThreadLeakDetector(DetectorBands bands, DetectorHistory history) {
        this(bands.threadLeak().multiple(), history);
    }

    public ThreadLeakDetector(double multiple, DetectorHistory history) {
        this.multiple = multiple;
        this.history = history;
    }

    @Override
    public String type() { return "thread_leak"; }
    @Override
    public String group() { return "threads"; }
    @Override
    public IncidentLifecycle.Kind kind() { return IncidentLifecycle.Kind.RATE_LEVEL; }
    @Override
    public String family() { return "C"; }
    @Override
    public Set<Integer> jdks() { return Set.of(17, 21, 25); }

    @Override
    public WindowVerdict evaluate(WindowInput in) {
        WindowVerdict gate = DetectorSupport.jdkGate(this, in);
        if (gate != null) return gate;
        if (history == null || in.window() == null) {
            return WindowVerdict.unknown("not enough data yet (0/30)", Set.of());
        }
        List<DetectorHistory.Point> points = history.points(in.jvm().jvmId(), DetectorMetrics.THREADS_LIVE, "", "avg",
                in.toMs() - 60L * 5_000L, in.toMs() + 1);
        if (points.size() < 30) {
            return WindowVerdict.unknown("not enough data yet (" + points.size() + "/30)", Set.of());
        }
        long[] times = new long[points.size()];
        double[] values = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            times[i] = points.get(i).ts();
            values[i] = points.get(i).value();
        }
        TrendService.Analysis analysis = TrendService.analyze(times, values, null);
        if (!analysis.known()) return WindowVerdict.unknown(analysis.reason(), Set.of());
        Double first = history.firstValue(in.jvm().jvmId(), DetectorMetrics.THREADS_LIVE, in.jvm().jvmStartMs() + 60_000L);
        double now = points.get(points.size() - 1).value();
        boolean rising = analysis.result().significant() && analysis.result().slopePerHour() > 0d
                && first != null && now >= multiple * first;
        return Verdicts.of(rising ? State.WARNING : State.OK, now, "count", null, new Band(first == null ? 0d : multiple * first, null), family(),
                DetectorSupport.band(HeapSizeAdvice.number(multiple) + "× the first live count") + "; first " + HeapSizeAdvice.number(first == null ? 0d : first),
                new Who("thread", "unknown", null, false), List.of(),
                List.of("look at what starts threads at unknown and never stops them"),
                "Live threads keep rising (" + HeapSizeAdvice.number(first == null ? 0d : first) + " -> " + HeapSizeAdvice.number(now) + ").",
                "Live threads " + HeapSizeAdvice.number(now),
                List.of("jdk.JavaThreadStatistics"), Set.of(), false);
    }
}

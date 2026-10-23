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
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;
import io.jafra.analyzer.v2.trend.TrendService;

/** Native memory growth. NMT, else RSS on 21/25, else pod usage on 17. */
@ApplicationScoped
public class NativeGrowthDetector implements Detector {
    private final double warningSlopePct;
    private final double criticalHeadroomMin;
    private final DetectorHistory history;

    @Inject
    public NativeGrowthDetector(DetectorBands bands, DetectorHistory history) {
        this(bands.nativeGrowth().warningSlopePct(), bands.nativeGrowth().criticalHeadroomMin(), history);
    }

    public NativeGrowthDetector(double warningSlopePct, double criticalHeadroomMin, DetectorHistory history) {
        this.warningSlopePct = warningSlopePct;
        this.criticalHeadroomMin = criticalHeadroomMin;
        this.history = history;
    }

    @Override
    public String type() {
        return "native_growth";
    }

    @Override
    public String group() {
        return "nmt";
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
        if (history == null || in.window() == null) {
            return WindowVerdict.unknown("not enough data yet (0/30)", Set.of());
        }
        Double limit = in.window().total(DetectorMetrics.CONTAINER_LIMIT).map(RollupRow::avg).orElse(in.settings().podMemoryLimitBytes());
        if (limit == null || limit <= 0d) {
            return WindowVerdict.unknown("no pod memory limit", Set.of(IncidentFlags.no_memory_limit.name()));
        }
        boolean nmt = in.window().total(DetectorMetrics.NMT_COMMITTED).isPresent() && in.settings().jdk() != 17;
        String series = nmt
                ? DetectorMetrics.NMT_COMMITTED
                : in.settings().jdk() == 17 ? DetectorMetrics.CONTAINER_USAGE : DetectorMetrics.CONTAINER_RSS;
        List<DetectorHistory.Point> points = history.points(in.jvm().jvmId(), series, "", "avg", in.toMs() - 60L * 60_000L, in.toMs() + 1);
        if (points.size() < 30) {
            return WindowVerdict.unknown("not enough data yet (" + points.size() + "/30)", flags(in, nmt));
        }
        long[] times = new long[points.size()];
        double[] values = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            times[i] = points.get(i).ts();
            values[i] = points.get(i).value();
        }
        TrendService.Analysis analysis = TrendService.analyze(times, values, limit);
        if (!analysis.known()) {
            return WindowVerdict.unknown(analysis.reason(), flags(in, nmt));
        }
        double slope = analysis.result().slopePerHour();
        boolean rising = analysis.result().significant() && slope > 0d;
        DetectorSupport.Headroom headroom = DetectorSupport.headroom(limit, latest(in, DetectorMetrics.CONTAINER_USAGE), slope);
        double minutes = headroom.minutes() == null ? Double.POSITIVE_INFINITY : headroom.minutes();
        State state = State.OK;
        if (rising && minutes < criticalHeadroomMin) {
            state = State.CRITICAL;
        } else if (rising && slope > limit * warningSlopePct / 100d) {
            state = State.WARNING;
        }
        String who = nmt ? "nmt" : series;
        String reason = DetectorSupport.band(HeapSizeAdvice.number(warningSlopePct) + "% of the pod limit per hour / "
                + HeapSizeAdvice.number(criticalHeadroomMin) + " min headroom");
        if (!nmt) {
            reason += "; nmt off, series " + series;
        }
        if (in.samples() == null || in.samples().rows("malloc").isEmpty()) {
            reason += "; no native allocation samples (async-profiler nativemem off)";
        }
        String todo = nmt
                ? "look at the fastest NMT category"
                : "enable NMT with the pod annotation jafra.io/jdk-nmt: \"enabled\"";
        return Verdicts.of(
                state,
                slope,
                "bytes_per_h",
                limit,
                new Band(limit * warningSlopePct / 100d, null),
                family(),
                reason,
                new Who(nmt ? "interface" : "site", who, null, !nmt),
                List.of(),
                List.of(todo),
                "Native (non-heap) memory keeps growing by " + HeapSizeAdvice.bytes(slope) + "/h.",
                "Native growth " + HeapSizeAdvice.bytes(slope) + "/h",
                List.of(nmt ? "jdk.NativeMemoryUsage" : "jdk.ContainerMemoryUsage"),
                flags(in, nmt),
                false);
    }

    private static Set<String> flags(WindowInput in, boolean nmt) {
        Set<String> flags = new LinkedHashSet<>();
        if (!nmt) {
            flags.add("nmt_off");
            if (in.settings().jdk() == 17) {
                flags.add("estimate");
            }
        }
        return flags;
    }

    private static Double latest(WindowInput in, String metric) {
        return in.window().total(metric).map(RollupRow::avg).orElse(null);
    }
}

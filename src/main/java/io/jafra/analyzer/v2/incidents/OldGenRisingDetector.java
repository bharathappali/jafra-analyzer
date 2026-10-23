package io.jafra.analyzer.v2.incidents;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorHistory;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.trend.TrendMath;
import io.jafra.analyzer.v2.trend.TrendService;

/** Live set after GC rising toward the heap max. */
@ApplicationScoped
public class OldGenRisingDetector implements Detector {
    private final double warningMin;
    private final double criticalMin;
    private final double occupancyPct;
    private final HeapSizeAdvice advice;
    private final DetectorHistory history;

    @Inject
    public OldGenRisingDetector(DetectorBands bands, HeapSizeAdvice advice, DetectorHistory history) {
        this(bands.oldGenRising().warningMin(), bands.oldGenRising().criticalMin(), bands.oldGenRising().occupancyPct(), advice, history);
    }

    public OldGenRisingDetector(double warningMin, double criticalMin, double occupancyPct, HeapSizeAdvice advice, DetectorHistory history) {
        this.warningMin = warningMin;
        this.criticalMin = criticalMin;
        this.occupancyPct = occupancyPct;
        this.advice = advice;
        this.history = history;
    }

    @Override
    public String type() {
        return "old_gen_rising";
    }

    @Override
    public String group() {
        return "heap";
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
            return WindowVerdict.unknown("no heap.after_gc.bytes series", Set.of());
        }
        List<DetectorHistory.Point> points = history.points(
                in.jvm().jvmId(), DetectorMetrics.HEAP_AFTER_GC_BYTES, "", "min", in.toMs() - 60L * 60_000L, in.toMs() + 1);
        long[] times = new long[points.size()];
        double[] values = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            times[i] = points.get(i).ts();
            values[i] = points.get(i).value();
        }
        Double heapMax = in.window().total(DetectorMetrics.HEAP_MAX_BYTES).map(row -> row.avg()).orElse(in.settings().heapMaxBytes());
        TrendService.Analysis analysis = TrendService.analyze(times, values, heapMax);
        if (!analysis.known()) {
            return WindowVerdict.unknown(analysis.reason(), Set.of());
        }
        TrendMath.Result trend = analysis.result();
        if (!trend.significant() || trend.slopePerHour() <= 0d || "plateau".equals(trend.shape()) || "staircase".equals(trend.shape())) {
            return WindowVerdict.ok(0d, "min", heapMax, new Band(warningMin, criticalMin), family(),
                    "band " + HeapSizeAdvice.number(warningMin) + " / " + HeapSizeAdvice.number(criticalMin) + " min (proposal, pending KUSMA)",
                    Set.of("estimate"));
        }
        double mid = trend.timeToLimitMin() == null ? Double.POSITIVE_INFINITY : trend.timeToLimitMin().mid();
        double occupancy = in.window().total(DetectorMetrics.HEAP_OCCUPANCY_PCT).map(row -> row.min()).orElse(0d);
        State state = State.OK;
        if (mid < criticalMin || occupancy >= occupancyPct) {
            state = State.CRITICAL;
        } else if (mid < warningMin) {
            state = State.WARNING;
        }
        Who who = AllocSites.allocatingClass(in);
        List<WhereFrame> where = AllocSites.where(in);
        String site = where.isEmpty() ? "unknown" : where.get(0).frame();
        List<String> todo = new ArrayList<>();
        todo.add("take a heap dump (jcmd " + in.jvm().jvmId() + " GC.heap_dump) and look at what holds the growing objects");
        todo.add("more heap only buys time: at " + HeapSizeAdvice.bytes(trend.slopePerHour()) + "/h the heap is full in about "
                + HeapSizeAdvice.number(mid) + " min");
        todo.add(advice.todo(in.settings(), in.window()));
        String reason = DetectorSupport.band(HeapSizeAdvice.number(warningMin) + " / " + HeapSizeAdvice.number(criticalMin) + " min");
        reason += "; jdk.OldObjectSample is off, so no leak path; flags read from jvmArguments; JAVA_TOOL_OPTIONS unverified";
        if (where.isEmpty()) {
            reason += "; no allocation samples";
        }
        return Verdicts.of(
                state,
                mid,
                "min",
                heapMax,
                new Band(warningMin, criticalMin),
                family(),
                reason,
                who,
                where,
                todo,
                "Memory left after each GC keeps growing; at this rate the heap is full in about " + HeapSizeAdvice.number(mid) + " min.",
                "Old generation is full in about " + HeapSizeAdvice.number(mid) + " min",
                List.of("jdk.GCHeapSummary", "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB"),
                Set.of("estimate"),
                false);
    }
}

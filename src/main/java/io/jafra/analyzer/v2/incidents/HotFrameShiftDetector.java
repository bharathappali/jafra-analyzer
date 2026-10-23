package io.jafra.analyzer.v2.incidents;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;
import io.jafra.analyzer.v2.store.V2Store.SampleRow;

/** Jensen-Shannon shift of the top CPU frames, and CPU above its baseline. */
@ApplicationScoped
public class HotFrameShiftDetector implements Detector {
    private final double distanceMin;
    private final double cpuMultiple;
    private final int minSamples;

    @Inject
    public HotFrameShiftDetector(DetectorBands bands) {
        this(bands.hotFrameShift().distance(), bands.hotFrameShift().cpuMultiple(), bands.hotFrameShift().minSamples());
    }

    public HotFrameShiftDetector(double distanceMin, double cpuMultiple, int minSamples) {
        this.distanceMin = distanceMin;
        this.cpuMultiple = cpuMultiple;
        this.minSamples = minSamples;
    }

    @Override
    public String type() {
        return "hot_frame_shift";
    }

    @Override
    public String group() {
        return "cpu";
    }

    @Override
    public IncidentLifecycle.Kind kind() {
        return IncidentLifecycle.Kind.RATE_LEVEL;
    }

    @Override
    public String family() {
        return "E";
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
        if (in.samples() == null || in.baselines() == null || in.window() == null) {
            return WindowVerdict.unknown("low_confidence", Set.of("low_confidence"));
        }
        List<SampleRow> now = in.samples().rows("cpu");
        List<SampleRow> before = in.samples().recent("cpu", 120);
        if (now.size() < minSamples || before.size() < minSamples) {
            return WindowVerdict.unknown("low_confidence", Set.of("low_confidence"));
        }
        double used = in.window().total(DetectorMetrics.CPU_USED_CORES).map(RollupRow::avg).orElse(0d);
        Baseline baseline = in.baselines().of(DetectorMetrics.CPU_USED_CORES, "", "cpu", "avg");
        double median = baseline == null || baseline.median() == null ? 0d : baseline.median();
        Map<String, Double> p = top(now, in);
        Map<String, Double> q = top(before, in);
        double distance = distance(p, q);
        boolean hot = distance >= distanceMin && used >= cpuMultiple * median;
        String frame = "other";
        double gain = 0d;
        for (String key : p.keySet()) {
            double delta = p.get(key) - q.getOrDefault(key, 0d);
            if (delta > gain) {
                gain = delta;
                frame = key;
            }
        }
        return Verdicts.of(
                hot ? State.WARNING : State.OK,
                distance,
                "distance",
                1d,
                new Band(distanceMin, null),
                family(),
                DetectorSupport.band(HeapSizeAdvice.number(distanceMin) + " JS and " + HeapSizeAdvice.number(cpuMultiple) + "× CPU")
                        + "; share_a/share_b for " + frame,
                new Who("site", frame, gain, true),
                List.of(new WhereFrame(frame, gain, List.of("jdk.ExecutionSample"))),
                List.of("the CPU profile changed: compare " + frame + " before and after"),
                "CPU went up and moved to different code.",
                "CPU profile distance " + HeapSizeAdvice.number(distance),
                List.of("jdk.ExecutionSample"),
                Set.of("estimate"),
                false);
    }

    /** Jensen-Shannon distance, log base 2, range 0–1. */
    public static double distance(Map<String, Double> left, Map<String, Double> right) {
        Map<String, Double> keys = new LinkedHashMap<>();
        keys.putAll(left);
        keys.putAll(right);
        double jsd = 0d;
        for (String key : keys.keySet()) {
            double p = left.getOrDefault(key, 0d);
            double q = right.getOrDefault(key, 0d);
            double m = 0.5d * (p + q);
            if (p > 0d && m > 0d) {
                jsd += 0.5d * p * (Math.log(p / m) / Math.log(2));
            }
            if (q > 0d && m > 0d) {
                jsd += 0.5d * q * (Math.log(q / m) / Math.log(2));
            }
        }
        return Math.sqrt(Math.max(0d, jsd));
    }

    private static Map<String, Double> top(List<SampleRow> rows, WindowInput in) {
        Map<String, Double> weight = new LinkedHashMap<>();
        double total = 0d;
        for (SampleRow row : rows) {
            String frame = DetectorSupport.appFrame(in.samples().stack(row.stackId()));
            if (frame.isBlank()) {
                frame = "other";
            }
            weight.merge(frame, row.weight(), Double::sum);
            total += row.weight();
        }
        Map<String, Double> shares = new LinkedHashMap<>();
        if (total <= 0d) {
            return shares;
        }
        List<Map.Entry<String, Double>> ranked = new java.util.ArrayList<>(weight.entrySet());
        ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        double kept = 0d;
        int taken = 0;
        for (Map.Entry<String, Double> entry : ranked) {
            if (taken == 50) {
                break;
            }
            double share = entry.getValue() / total;
            shares.put(entry.getKey(), share);
            kept += share;
            taken++;
        }
        if (kept < 1d) {
            shares.merge("other", 1d - kept, Double::sum);
        }
        return shares;
    }
}

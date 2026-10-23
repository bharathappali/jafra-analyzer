package io.jafra.analyzer.v2.incidents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.frames.FrameService;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.RobustZ;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.rollup.Frame;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;
import io.jafra.analyzer.v2.store.V2Store.SampleRow;

/** CPU jumped above its baseline. Where comes from frame gainers. */
@ApplicationScoped
public class CpuJumpDetector implements Detector {
    private final double zMin;
    private final double multiple;

    @Inject
    public CpuJumpDetector(DetectorBands bands) {
        this(bands.cpuJump().z(), bands.cpuJump().multiple());
    }

    public CpuJumpDetector(double zMin, double multiple) {
        this.zMin = zMin;
        this.multiple = multiple;
    }

    @Override
    public String type() {
        return "cpu_jump";
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
        return "D";
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
        if (in.window() == null || in.baselines() == null || in.window().total(DetectorMetrics.CPU_USED_CORES).isEmpty()) {
            return WindowVerdict.unknown("no " + DetectorMetrics.CPU_USED_CORES + " row", Set.of());
        }
        double value = in.window().total(DetectorMetrics.CPU_USED_CORES).map(RollupRow::avg).orElse(0d);
        Baseline baseline = in.baselines().of(DetectorMetrics.CPU_USED_CORES, "", "cpu", "avg");
        int n = baseline == null || baseline.median() == null ? 0 : baseline.n();
        if (n < RobustZ.WARMUP || baseline.mad() == null) {
            return WindowVerdict.unknown("baseline warming up (" + n + "/12)", Set.of());
        }
        double z = RobustZ.z(value, baseline.median(), baseline.mad(), n);
        boolean hot = z >= zMin && value >= multiple * baseline.median();
        List<WhereFrame> where = gainers(in);
        String frame = where.isEmpty() ? "unknown" : where.get(0).frame();
        String reason = DetectorSupport.band("z " + HeapSizeAdvice.number(zMin) + " and " + HeapSizeAdvice.number(multiple) + "× median");
        if (!where.isEmpty() && !FrameService.significant(200, 0.1, 200, 0.2)) {
            reason += "; gainer significance uses FrameService";
        } else if (where.isEmpty()) {
            reason += "; no significant gainer";
        }
        List<RollupRow> pools = in.window().breakdown(DetectorMetrics.CPU_THREAD_PCT, "pool");
        return Verdicts.of(
                hot ? State.WARNING : State.OK,
                value,
                "cores",
                baseline.median(),
                new Band(multiple * baseline.median(), null),
                family(),
                reason,
                new Who("thread", pools.isEmpty() ? "unknown" : pools.get(0).key(), null, true),
                where,
                List.of("look at what changed in " + frame + " (+" + HeapSizeAdvice.number(where.isEmpty() ? 0d : where.get(0).sharePct()) + " pts)"),
                "CPU use jumped to " + HeapSizeAdvice.number(value) + " cores from a normal " + HeapSizeAdvice.number(baseline.median()) + ".",
                "CPU " + HeapSizeAdvice.number(value) + " cores",
                List.of("jdk.CPULoad"),
                RobustZ.unproven(n) ? Set.of("estimate", "unproven_baseline") : Set.of("estimate"),
                false);
    }

    private static List<WhereFrame> gainers(WindowInput in) {
        if (in.samples() == null) {
            return List.of();
        }
        Map<String, Double> now = shares(in.samples().rows("cpu"), in);
        Map<String, Double> before = shares(in.samples().recent("cpu", 120), in);
        List<WhereFrame> frames = new ArrayList<>();
        for (Map.Entry<String, Double> entry : now.entrySet()) {
            double delta = entry.getValue() - before.getOrDefault(entry.getKey(), 0d);
            if (delta > 0d) {
                boolean significant = FrameService.significant(200, before.getOrDefault(entry.getKey(), 0d) / 100d, 200, entry.getValue() / 100d);
                frames.add(new WhereFrame(entry.getKey() + (significant ? "" : " (not significant)"), delta, List.of("jdk.ExecutionSample")));
            }
        }
        frames.sort(Comparator.comparingDouble(WhereFrame::sharePct).reversed());
        return frames.size() > 5 ? frames.subList(0, 5) : frames;
    }

    private static Map<String, Double> shares(List<SampleRow> rows, WindowInput in) {
        Map<String, Double> weight = new LinkedHashMap<>();
        double total = 0d;
        for (SampleRow row : rows) {
            String frame = DetectorSupport.appFrame(in.samples().stack(row.stackId()));
            if (frame.isBlank()) {
                continue;
            }
            weight.merge(frame, row.weight(), Double::sum);
            total += row.weight();
        }
        Map<String, Double> shares = new LinkedHashMap<>();
        if (total <= 0d) {
            return shares;
        }
        for (Map.Entry<String, Double> entry : weight.entrySet()) {
            shares.put(entry.getKey(), 100d * entry.getValue() / total);
        }
        return shares;
    }
}

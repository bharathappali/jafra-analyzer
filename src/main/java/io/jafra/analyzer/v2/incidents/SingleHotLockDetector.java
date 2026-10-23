package io.jafra.analyzer.v2.incidents;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.SampleRow;

/** One lock or one site holds most of the filtered wait. Warning only. */
@ApplicationScoped
public class SingleHotLockDetector implements Detector {
    private final double sharePct;
    private final double minThreadS;
    private final int minSamples;
    private final List<String> idle;

    @Inject
    public SingleHotLockDetector(DetectorBands bands) {
        this(bands.singleHotLock().sharePct(), bands.singleHotLock().minThreadS(), bands.singleHotLock().minSamples(),
                List.of(bands.lockContention().idleFrames().split(",")));
    }

    public SingleHotLockDetector(double sharePct, double minThreadS, int minSamples, List<String> idle) {
        this.sharePct = sharePct;
        this.minThreadS = minThreadS;
        this.minSamples = minSamples;
        this.idle = idle;
    }

    @Override
    public String type() { return "single_hot_lock"; }
    @Override
    public String group() { return "locks"; }
    @Override
    public IncidentLifecycle.Kind kind() { return IncidentLifecycle.Kind.RATE_LEVEL; }
    @Override
    public String family() { return "E"; }
    @Override
    public Set<Integer> jdks() { return Set.of(17, 21, 25); }

    @Override
    public WindowVerdict evaluate(WindowInput in) {
        WindowVerdict gate = DetectorSupport.jdkGate(this, in);
        if (gate != null) return gate;
        if (in.samples() == null) return WindowVerdict.unknown("low_confidence", Set.of("low_confidence"));
        Map<String, Double> byLock = new LinkedHashMap<>();
        int kept = 0;
        double waitMs = 0d;
        double seconds = Math.max(0.001d, (in.toMs() - in.fromMs()) / 1000d);
        for (SampleRow sample : in.samples().rows("lock")) {
            if (DetectorSupport.stackContains(in.samples().stack(sample.stackId()), idle)) continue;
            kept++;
            waitMs += sample.weight();
            byLock.merge(sample.lockClass() == null ? "unknown" : sample.lockClass(), sample.weight(), Double::sum);
        }
        if (kept < minSamples) return WindowVerdict.unknown("low_confidence", Set.of("low_confidence"));
        String lock = "unknown";
        double best = 0d;
        for (Map.Entry<String, Double> entry : byLock.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                lock = entry.getKey();
            }
        }
        double share = waitMs <= 0d ? 0d : 100d * best / waitMs;
        double value = (waitMs / 1000d) / seconds;
        State state = share >= sharePct && value >= minThreadS ? State.WARNING : State.OK;
        return Verdicts.of(state, share, "pct", 100d, new Band(sharePct, null), family(),
                DetectorSupport.band(HeapSizeAdvice.number(sharePct) + "% of filtered wait"),
                new Who("lock", lock, share, true), List.of(),
                List.of("shorten or split the critical section at " + lock),
                "One lock (" + lock + ") causes " + HeapSizeAdvice.number(share) + "% of all lock waiting.",
                "Hot lock " + lock,
                List.of("jdk.JavaMonitorEnter"), Set.of("estimate"), false);
    }
}

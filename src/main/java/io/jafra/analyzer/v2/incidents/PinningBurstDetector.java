package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.EventRow;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

/** Virtual-thread pinning. JDK 21 has no pin reason. */
@ApplicationScoped
public class PinningBurstDetector implements Detector {
    private final int minCount;
    private final double warningPct;
    private final double criticalPct;

    @Inject
    public PinningBurstDetector(DetectorBands bands) {
        this(bands.pinningBurst().minCount(), bands.pinningBurst().warningPct(), bands.pinningBurst().criticalPct());
    }

    public PinningBurstDetector(int minCount, double warningPct, double criticalPct) {
        this.minCount = minCount;
        this.warningPct = warningPct;
        this.criticalPct = criticalPct;
    }

    @Override
    public String type() { return "pinning_burst"; }
    @Override
    public String group() { return "vt"; }
    @Override
    public IncidentLifecycle.Kind kind() { return IncidentLifecycle.Kind.RATE_LEVEL; }
    @Override
    public String family() { return "A"; }
    @Override
    public Set<Integer> jdks() { return Set.of(21, 25); }

    @Override
    public WindowVerdict evaluate(WindowInput in) {
        WindowVerdict gate = DetectorSupport.jdkGate(this, in);
        if (gate != null) return gate;
        if (in.window() == null || in.window().total(DetectorMetrics.VT_PINNED_US).isEmpty()) {
            return WindowVerdict.unknown("no " + DetectorMetrics.VT_PINNED_US + " row", Set.of("lower_bound"));
        }
        RollupRow row = in.window().total(DetectorMetrics.VT_PINNED_US).get();
        double seconds = Math.max(0.001d, (in.toMs() - in.fromMs()) / 1000d);
        double carriers = carriers(in);
        double share = carriers <= 0d ? 0d : (row.sum() / 1_000_000d) / (carriers * seconds) * 100d;
        State state = State.OK;
        if (row.count() >= minCount && share >= criticalPct) state = State.CRITICAL;
        else if (row.count() >= minCount && share >= warningPct) state = State.WARNING;
        String site = in.settings().jdk() >= 25 ? reason(in) : "top pin site";
        String text = DetectorSupport.band(minCount + " pins and " + HeapSizeAdvice.number(warningPct) + "% / " + HeapSizeAdvice.number(criticalPct) + "%");
        if (in.settings().jdk() == 21) text += "; pin reason needs JDK 25";
        text += "; carriers from " + carrierSource(in);
        return Verdicts.of(state, share, "pct", 100d, new Band(warningPct, criticalPct), family(), text,
                new Who("site", site, share, false), List.of(),
                List.of("replace synchronized around I/O at unknown with a ReentrantLock"),
                "Virtual threads were pinned to carriers " + HeapSizeAdvice.number(share) + "% of the time.",
                "Pinning " + HeapSizeAdvice.number(share) + "%",
                List.of("jdk.VirtualThreadPinned"), Set.of("lower_bound"), false);
    }

    private static double carriers(WindowInput in) {
        String override = in.settings().argument("-Djdk.virtualThreadScheduler.parallelism").orElse(null);
        if (override != null) {
            try { return Double.parseDouble(override); } catch (NumberFormatException ignored) { return 1d; }
        }
        Double cpus = in.settings().effectiveCpus();
        return cpus == null ? 1d : cpus;
    }

    private static String carrierSource(WindowInput in) {
        return in.settings().argument("-Djdk.virtualThreadScheduler.parallelism").isPresent()
                ? "-Djdk.virtualThreadScheduler.parallelism" : "container.effective_cpus";
    }

    private static String reason(WindowInput in) {
        if (in.events() == null) return "unknown";
        for (EventRow event : in.events().of("vt.pinned")) {
            JsonNode fields = JsonFields.read(event.fields());
            if (fields != null && !fields.path("reason").asText("").isBlank()) return fields.path("reason").asText();
        }
        return "unknown";
    }
}

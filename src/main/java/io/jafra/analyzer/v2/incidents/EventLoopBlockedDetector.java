package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.IncidentFlags;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.SampleRow;

/** Event-loop threads blocked outside the selector. */
@ApplicationScoped
public class EventLoopBlockedDetector implements Detector {
    private final double warningPct;
    private final double criticalPct;
    private final int minSamples;
    private final List<String> prefixes;
    private final List<String> selectors;

    @Inject
    public EventLoopBlockedDetector(DetectorBands bands) {
        this(bands.eventLoopBlocked().warningPct(), bands.eventLoopBlocked().criticalPct(), bands.eventLoopBlocked().minSamples(),
                List.of(bands.eventLoopBlocked().prefixes().split(",")), List.of(bands.eventLoopBlocked().selectorFrames().split(",")));
    }

    public EventLoopBlockedDetector(double warningPct, double criticalPct, int minSamples, List<String> prefixes, List<String> selectors) {
        this.warningPct = warningPct;
        this.criticalPct = criticalPct;
        this.minSamples = minSamples;
        this.prefixes = prefixes;
        this.selectors = selectors;
    }

    @Override
    public String type() {
        return "event_loop_blocked";
    }

    @Override
    public String group() {
        return "threads";
    }

    @Override
    public IncidentLifecycle.Kind kind() {
        return IncidentLifecycle.Kind.RATE_LEVEL;
    }

    @Override
    public String family() {
        return "A";
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
        if (in.samples() == null) {
            return WindowVerdict.unknown("no wall-clock samples for event loops", Set.of());
        }
        int all = 0;
        int blocked = 0;
        String worst = "unknown";
        for (SampleRow sample : in.samples().rows("wall")) {
            if (!matches(sample.thread())) {
                continue;
            }
            all++;
            boolean selector = DetectorSupport.stackContains(in.samples().stack(sample.stackId()), selectors);
            if (!selector) {
                blocked++;
                worst = sample.thread();
            }
        }
        if (all < minSamples) {
            return WindowVerdict.unknown("no wall-clock samples for event loops", Set.of(IncidentFlags.busy_loop_included.name()));
        }
        double share = 100d * blocked / all;
        State state = share >= criticalPct ? State.CRITICAL : share >= warningPct ? State.WARNING : State.OK;
        return Verdicts.of(
                state,
                share,
                "pct",
                100d,
                new Band(warningPct, criticalPct),
                family(),
                DetectorSupport.band(HeapSizeAdvice.number(warningPct) + "% / " + HeapSizeAdvice.number(criticalPct) + "%"),
                new Who("thread", worst, share, true),
                List.of(),
                List.of("move unknown off the event loop (executeBlocking / a worker pool)"),
                "Event-loop threads were blocked " + HeapSizeAdvice.number(share) + "% of the time, stalling every connection they serve.",
                "Event loop blocked " + HeapSizeAdvice.number(share) + "%",
                List.of("jdk.ExecutionSample"),
                Set.of("estimate", IncidentFlags.busy_loop_included.name()),
                false);
    }

    private boolean matches(String thread) {
        if (thread == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (!prefix.isBlank() && thread.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

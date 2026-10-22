package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.InternalInputs;
import io.jafra.analyzer.v2.store.V2Store.EventRow;

/** ZGC allocation stalls. Other collectors stay OK. */
@ApplicationScoped
public class ZgcAllocStallDetector implements Detector {
    private final double criticalPct;
    private final HeapSizeAdvice advice;

    @Inject
    public ZgcAllocStallDetector(DetectorBands bands, HeapSizeAdvice advice) {
        this(bands.zgcAllocStall().criticalPct(), advice);
    }

    public ZgcAllocStallDetector(double criticalPct, HeapSizeAdvice advice) {
        this.criticalPct = criticalPct;
        this.advice = advice;
    }

    @Override
    public String type() {
        return "zgc_alloc_stall";
    }

    @Override
    public String group() {
        return "gc";
    }

    @Override
    public IncidentLifecycle.Kind kind() {
        return IncidentLifecycle.Kind.INSTANT;
    }

    @Override
    public String family() {
        return "B";
    }

    @Override
    public Set<Integer> jdks() {
        return Set.of(17, 21, 25);
    }

    @Override
    public WindowVerdict evaluate(WindowInput in) {
        if (!jdks().contains(in.settings().jdk())) {
            return WindowVerdict.unknown("needs JDK " + jdks(), Set.of("not_on_this_jdk"));
        }
        String collector = in.settings().oldCollector() + in.settings().youngCollector();
        if (!collector.toLowerCase().contains("zgc") && !collector.contains("Z")) {
            return WindowVerdict.ok(0d, "pct", null, new Band(0d, criticalPct), family(), "not ZGC", Set.of());
        }
        double micros = 0d;
        long count = 0L;
        var row = in.window().total(DetectorMetrics.GC_ZGC_STALL_US);
        if (row.isPresent()) {
            micros = row.get().sum();
            count = row.get().count();
        }
        if (count == 0L && micros == 0d) {
            return WindowVerdict.ok(0d, "pct", null, new Band(0d, criticalPct), family(),
                    "band any stall / " + criticalPct + "% (proposal, pending KUSMA)", Set.of());
        }
        double dt = Math.max(1d, (in.toMs() - in.fromMs()) / 1000d);
        double pct = (micros / 1_000_000d) / dt * 100d;
        State state = pct >= criticalPct ? State.CRITICAL : State.WARNING;
        String thread = worstThread(in);
        return new WindowVerdict(
                state,
                pct,
                "pct",
                null,
                new Band(0d, criticalPct),
                family(),
                "band any stall / " + criticalPct + "% (proposal, pending KUSMA)",
                new Who("thread", thread, null, false),
                List.of(),
                List.of(advice.todo(in.settings(), in.window()), "cut allocation at the top stall frame"),
                "Threads waited " + (long) (micros / 1000d) + " ms for ZGC to free memory before they could allocate.",
                "ZGC allocation stall",
                List.of("jdk.ZAllocationStall"),
                Set.of(),
                null);
    }

    private static String worstThread(WindowInput in) {
        String thread = "";
        long worst = -1L;
        for (EventRow event : in.events().internal(InternalInputs.ZGC_STALL)) {
            long duration = event.durationUs() == null ? 0L : event.durationUs();
            if (duration > worst) {
                worst = duration;
                thread = event.thread() == null ? "" : event.thread();
            }
        }
        return thread.isBlank() ? "unknown" : thread;
    }
}

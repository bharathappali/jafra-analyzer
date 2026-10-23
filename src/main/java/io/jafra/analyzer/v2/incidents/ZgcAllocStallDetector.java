package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;

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
        if (!isZgc(in.settings().youngCollector()) && !isZgc(in.settings().oldCollector())) {
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
        Stall worst = worstThread(in);
        List<WhereFrame> where = stallFrames(in);
        String frame = where.isEmpty() ? "unknown" : where.get(0).frame();
        return new WindowVerdict(
                state,
                pct,
                "pct",
                null,
                new Band(0d, criticalPct),
                family(),
                "band any stall / " + criticalPct + "% (proposal, pending KUSMA)",
                new Who("thread", worst.name(), worst.share(), false),
                where,
                List.of(advice.todo(in.settings(), in.window()), "cut allocation at " + frame),
                "Threads waited " + (long) (micros / 1000d) + " ms for ZGC to free memory before they could allocate.",
                "ZGC allocation stall",
                List.of("jdk.ZAllocationStall"),
                Set.of(),
                null);
    }

    private static boolean isZgc(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("zgc") || "z".equals(lower);
    }

    private static Stall worstThread(WindowInput in) {
        java.util.Map<String, Long> totals = new java.util.LinkedHashMap<>();
        long all = 0L;
        for (EventRow event : in.events().internal(InternalInputs.ZGC_STALL)) {
            long duration = event.durationUs() == null ? 0L : event.durationUs();
            String thread = event.thread() == null || event.thread().isBlank() ? "unknown" : event.thread();
            totals.merge(thread, duration, Long::sum);
            all += duration;
        }
        String name = "unknown";
        long worst = -1L;
        for (java.util.Map.Entry<String, Long> entry : totals.entrySet()) {
            if (entry.getValue() > worst) {
                worst = entry.getValue();
                name = entry.getKey();
            }
        }
        Double share = all <= 0L || worst < 0L ? null : 100d * worst / all;
        return new Stall(name, share);
    }

    private static List<WhereFrame> stallFrames(WindowInput in) {
        java.util.Map<String, Long> weight = new java.util.LinkedHashMap<>();
        long total = 0L;
        for (EventRow event : in.events().internal(InternalInputs.ZGC_STALL)) {
            long duration = event.durationUs() == null ? 0L : Math.max(1L, event.durationUs());
            String frame = frameOf(event);
            if (frame.isBlank()) {
                continue;
            }
            weight.merge(frame, duration, Long::sum);
            total += duration;
        }
        if (total <= 0L) {
            return List.of();
        }
        List<java.util.Map.Entry<String, Long>> ranked = new java.util.ArrayList<>(weight.entrySet());
        ranked.sort(java.util.Comparator.comparingLong((java.util.Map.Entry<String, Long> entry) -> entry.getValue()).reversed());
        List<WhereFrame> frames = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Long> entry : ranked) {
            if (frames.size() == 5) {
                break;
            }
            frames.add(new WhereFrame(entry.getKey(), 100d * entry.getValue() / total, List.of("jdk.ZAllocationStall")));
        }
        return frames;
    }

    private static String frameOf(EventRow event) {
        if (event.topFrame() != null && !event.topFrame().isBlank()) {
            return event.topFrame();
        }
        JsonNode fields = JsonFields.read(event.fields());
        return fields == null ? "" : fields.path("top_frame").asText("");
    }

    private record Stall(String name, Double share) {}
}

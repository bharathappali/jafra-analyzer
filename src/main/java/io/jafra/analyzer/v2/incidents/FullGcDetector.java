package io.jafra.analyzer.v2.incidents;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorHistory;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.FullGcClassifier;
import io.jafra.analyzer.v2.store.V2Store.EventRow;

/** A full GC is a pause whose collector name is on the shared full-collector list. */
@ApplicationScoped
public class FullGcDetector implements Detector {
    private static final Set<String> EVALUATED = Set.of(
            "G1New", "G1Old", "G1Full", "ParallelScavenge", "ParallelOld", "DefNew", "SerialOld");

    private final int criticalCount;
    private final int windows;
    private final DetectorHistory history;
    private final HeapSizeAdvice advice;
    private final FullGcClassifier classifier = FullGcClassifier.defaults();

    @Inject
    public FullGcDetector(DetectorBands bands, DetectorHistory history, HeapSizeAdvice advice) {
        this(bands.fullGc().criticalCount(), bands.fullGc().windowCount(), history, advice);
    }

    public FullGcDetector(int criticalCount, int windows, DetectorHistory history, HeapSizeAdvice advice) {
        this.criticalCount = criticalCount;
        this.windows = windows;
        this.history = history;
        this.advice = advice;
    }

    @Override
    public String type() {
        return "full_gc";
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
        String collector = in.settings().oldCollector().isBlank() ? in.settings().youngCollector() : in.settings().oldCollector();
        if (!EVALUATED.contains(in.settings().youngCollector()) && !EVALUATED.contains(in.settings().oldCollector())) {
            return WindowVerdict.ok(0d, "count", null, new Band(1d, (double) criticalCount), family(),
                    "no full GCs on " + (collector.isBlank() ? "unknown" : collector), Set.of());
        }
        EventRow longest = null;
        int inWindow = 0;
        for (EventRow event : in.events().of("gc.pause")) {
            JsonNode fields = JsonFields.read(event.fields());
            String name = fields == null ? "" : fields.path("collector").asText("");
            String g1Type = fields == null ? "" : fields.path("g1_type").asText("");
            if (classifier.isFull(name) || "Full".equals(g1Type)) {
                inWindow++;
                if (longest == null || duration(event) > duration(longest)) {
                    longest = event;
                }
            }
        }
        int prior;
        try {
            prior = prior(in);
        } catch (RuntimeException error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            return WindowVerdict.unknown("gc.full.count history failed: " + message, Set.of());
        }
        double value = inWindow + prior;
        Band band = new Band(1d, (double) criticalCount);
        String reason = "band 1 full GC / " + criticalCount + " in " + windows + " windows (proposal, pending KUSMA)";
        if (inWindow == 0) {
            return WindowVerdict.ok(value, "count", null, band, family(), reason, Set.of());
        }
        State state = value >= criticalCount ? State.CRITICAL : State.WARNING;
        String cause = cause(longest);
        boolean explicit = "System.gc()".equals(cause);
        EventRow system = explicit ? systemGc(in) : null;
        String frame = system == null || system.topFrame() == null || system.topFrame().isBlank() ? "unknown" : system.topFrame();
        List<WhereFrame> where = explicit
                ? List.of(new WhereFrame(frame, 100d, List.of("jdk.SystemGC")))
                : AllocSites.where(in);
        List<String> todo = new ArrayList<>();
        if (explicit) {
            todo.add("remove the System.gc() call at " + frame + " or add -XX:+DisableExplicitGC");
        } else {
            todo.add(advice.todo(in.settings(), in.window()));
            if (!where.isEmpty()) {
                todo.add("cut allocation at " + where.get(0).frame());
            }
        }
        long ms = longest == null || longest.durationUs() == null ? 0L : longest.durationUs() / 1000L;
        return new WindowVerdict(
                state,
                value,
                "count",
                null,
                band,
                family(),
                reason,
                new Who("site", cause.isBlank() ? collector : cause, null, false),
                where,
                todo,
                "The JVM stopped for a full GC of the whole heap (" + ms + " ms).",
                "Full GC " + cause,
                List.of("jdk.GarbageCollection"),
                Set.of(),
                null);
    }

    private int prior(WindowInput in) {
        if (history == null || in.jvm() == null || in.jvm().jvmId() <= 0) {
            return 0;
        }
        return (int) Math.round(history.sum(
                in.jvm().jvmId(), DetectorMetrics.GC_FULL_COUNT, "", "sum", windows - 1, in.fromMs() - 1));
    }

    private static long duration(EventRow event) {
        return event.durationUs() == null ? 0L : event.durationUs();
    }

    private static String cause(EventRow event) {
        if (event == null) {
            return "";
        }
        JsonNode fields = JsonFields.read(event.fields());
        return fields == null ? "" : fields.path("cause").asText("");
    }

    private static EventRow systemGc(WindowInput in) {
        List<EventRow> calls = in.events().of("gc.system_gc");
        return calls.isEmpty() ? null : calls.get(0);
    }
}

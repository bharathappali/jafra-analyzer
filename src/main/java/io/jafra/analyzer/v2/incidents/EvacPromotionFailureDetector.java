package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.store.V2Store.EventRow;

/** Any evacuation or promotion failure is a critical full-heap copy failure. */
@ApplicationScoped
public class EvacPromotionFailureDetector implements Detector {
    private final HeapSizeAdvice advice;

    @Inject
    public EvacPromotionFailureDetector(HeapSizeAdvice advice) {
        this.advice = advice;
    }

    @Override
    public String type() {
        return "evac_promotion_failure";
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
        List<EventRow> failures = new java.util.ArrayList<>();
        failures.addAll(in.events().of("gc.evac_failed"));
        failures.addAll(in.events().of("gc.promotion_failed"));
        if (failures.isEmpty()) {
            return WindowVerdict.ok(0d, "count", null, new Band(null, 1d), family(),
                    "band 1 failure (proposal, pending KUSMA)", Set.of());
        }
        EventRow first = failures.get(0);
        JsonNode fields = JsonFields.read(first.fields());
        String objects = fields == null ? "0" : fields.path("object_count").asText("0");
        String bytes = fields == null ? "0" : fields.path("total_size_bytes").asText("0");
        String collector = in.settings().oldCollector().isBlank() ? in.settings().youngCollector() : in.settings().oldCollector();
        List<String> todo = new java.util.ArrayList<>();
        todo.add(advice.todo(in.settings(), in.window()));
        if (collector.startsWith("G1")) {
            String ihop = in.settings().argument("-XX:InitiatingHeapOccupancyPercent").orElse("adaptive IHOP");
            todo.add("start marking earlier: lower -XX:InitiatingHeapOccupancyPercent (now " + ihop + ") or raise -XX:G1ReservePercent");
        }
        return new WindowVerdict(
                State.CRITICAL,
                (double) failures.size(),
                "count",
                null,
                new Band(null, 1d),
                family(),
                "band 1 failure (proposal, pending KUSMA)",
                new Who("site", collector + " · " + objects + " objects, " + bytes + " failed to copy", null, false),
                AllocSites.where(in),
                todo,
                "The GC ran out of space to copy live objects, so the pause got much longer.",
                "Evacuation or promotion failed",
                List.of("jdk.EvacuationFailed", "jdk.PromotionFailed"),
                Set.of(),
                null);
    }
}

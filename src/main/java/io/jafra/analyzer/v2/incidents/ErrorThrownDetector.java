package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.JsonNode;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.EventRow;

/** An Error other than OutOfMemoryError. StackOverflowError is critical. */
@ApplicationScoped
public class ErrorThrownDetector implements Detector {
    @Override
    public String type() { return "error_thrown"; }
    @Override
    public String group() { return "exceptions"; }
    @Override
    public IncidentLifecycle.Kind kind() { return IncidentLifecycle.Kind.INSTANT; }
    @Override
    public String family() { return "B"; }
    @Override
    public Set<Integer> jdks() { return Set.of(17, 21, 25); }

    @Override
    public WindowVerdict evaluate(WindowInput in) {
        WindowVerdict gate = DetectorSupport.jdkGate(this, in);
        if (gate != null) return gate;
        if (in.events() == null) return WindowVerdict.unknown("no error events", Set.of());
        EventRow hit = null;
        boolean capped = false;
        int seen = 0;
        for (EventRow event : in.events().of("error")) {
            seen++;
            JsonNode fields = JsonFields.read(event.fields());
            String type = fields == null ? "" : fields.path("thrownClass").asText("");
            if (type.endsWith("OutOfMemoryError")) continue;
            hit = event;
        }
        if (seen >= 200) capped = true;
        if (hit == null) return WindowVerdict.ok(0d, "count", null, new Band(null, 1d), family(), "no Error other than OutOfMemoryError", Set.of());
        JsonNode fields = JsonFields.read(hit.fields());
        String type = fields == null ? "unknown" : fields.path("thrownClass").asText("unknown");
        String message = fields == null ? "" : fields.path("message").asText("");
        boolean stack = type.endsWith("StackOverflowError");
        String frame = hit.topFrame() == null ? "unknown" : hit.topFrame();
        String reason = DetectorSupport.band(stack ? "StackOverflowError" : "any other Error");
        if (capped) reason += "; error events capped at 200 per window";
        return Verdicts.of(stack ? State.CRITICAL : State.WARNING, 1d, "count", null, new Band(null, stack ? 1d : null), family(), reason,
                new Who("class", type + ": " + message, null, false),
                List.of(new WhereFrame(frame, 100d, List.of("jdk.JavaErrorThrow"))),
                List.of(stack ? "find the repeating frame " + frame + " (recursion)" : "check " + type),
                "The JVM threw " + type + ".",
                type,
                List.of("jdk.JavaErrorThrow"), Set.of(), false);
    }
}

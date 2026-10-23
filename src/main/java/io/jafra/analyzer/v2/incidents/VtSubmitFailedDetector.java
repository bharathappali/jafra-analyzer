package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.JsonNode;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.EventRow;

/** The virtual-thread scheduler rejected a task. */
@ApplicationScoped
public class VtSubmitFailedDetector implements Detector {
    @Override
    public String type() { return "vt_submit_failed"; }
    @Override
    public String group() { return "vt"; }
    @Override
    public IncidentLifecycle.Kind kind() { return IncidentLifecycle.Kind.INSTANT; }
    @Override
    public String family() { return "B"; }
    @Override
    public Set<Integer> jdks() { return Set.of(21, 25); }

    @Override
    public WindowVerdict evaluate(WindowInput in) {
        WindowVerdict gate = DetectorSupport.jdkGate(this, in);
        if (gate != null) return gate;
        if (in.events() == null) return WindowVerdict.unknown("no vt.submit_failed events", Set.of());
        List<EventRow> rows = in.events().of("vt.submit_failed");
        if (rows.isEmpty()) {
            return WindowVerdict.ok(0d, "count", null, new Band(null, 1d), family(), "no submit failure", Set.of());
        }
        EventRow event = rows.get(0);
        JsonNode fields = JsonFields.read(event.fields());
        String message = fields == null ? "" : fields.path("exceptionMessage").asText("");
        String frame = event.topFrame() == null ? "unknown" : event.topFrame();
        return Verdicts.of(State.CRITICAL, (double) rows.size(), "count", null, new Band(null, 1d), family(),
                DetectorSupport.band("any submit failure"),
                new Who("thread", event.thread() + ": " + message, null, false),
                List.of(new WhereFrame(frame, 100d, List.of("jdk.VirtualThreadSubmitFailed"))),
                List.of("look at what floods the scheduler", "check carriers blocked by pinning"),
                "The JVM could not schedule a virtual thread.",
                "Virtual thread submit failed",
                List.of("jdk.VirtualThreadSubmitFailed"), Set.of(), false);
    }
}

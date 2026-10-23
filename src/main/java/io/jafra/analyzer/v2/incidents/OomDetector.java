package io.jafra.analyzer.v2.incidents;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.IncidentFlags;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.EventRow;

/** JavaErrorThrow OutOfMemoryError. Metaspace-only events stay with metaspace_growth. */
@ApplicationScoped
public class OomDetector implements Detector {
    private final HeapSizeAdvice advice;

    @Inject
    public OomDetector(HeapSizeAdvice advice) {
        this.advice = advice;
    }

    @Override
    public String type() {
        return "oom";
    }

    @Override
    public String group() {
        return "heap";
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
        WindowVerdict gate = DetectorSupport.jdkGate(this, in);
        if (gate != null) {
            return gate;
        }
        if (in.events() == null) {
            return WindowVerdict.unknown("no oom events", Set.of(IncidentFlags.known_gap_prealloc_oom.name()));
        }
        List<EventRow> hits = new ArrayList<>();
        for (EventRow event : in.events().of("oom")) {
            if (counts(event)) {
                hits.add(event);
            }
        }
        Set<String> flags = Set.of(IncidentFlags.known_gap_prealloc_oom.name());
        if (hits.isEmpty()) {
            return WindowVerdict.ok(0d, "count", null, new Band(null, 1d), family(), "no JavaErrorThrow OOM", flags);
        }
        EventRow event = hits.get(0);
        JsonNode fields = JsonFields.read(event.fields());
        String message = fields == null ? "" : fields.path("message").asText("");
        String thread = event.thread() == null || event.thread().isBlank() ? "unknown" : event.thread();
        String frame = event.topFrame() == null || event.topFrame().isBlank() ? "unknown" : event.topFrame();
        List<WhereFrame> where = List.of(new WhereFrame(frame, 100d, List.of("jdk.JavaErrorThrow")));
        return Verdicts.of(
                State.CRITICAL,
                (double) hits.size(),
                "count",
                null,
                new Band(null, 1d),
                family(),
                "band any JavaErrorThrow OOM (proposal, pending KUSMA); flags read from jvmArguments; JAVA_TOOL_OPTIONS unverified",
                new Who("thread", thread + ": " + message, null, false),
                where,
                List.of(todo(in, message)),
                "The JVM ran out of memory (" + message + ").",
                "OutOfMemoryError: " + message,
                List.of("jdk.JavaErrorThrow"),
                flags,
                false);
    }

    private String todo(WindowInput in, String message) {
        String text = message == null ? "" : message;
        if (text.contains("Java heap space") || text.contains("GC overhead limit exceeded")) {
            return advice.todo(in.settings(), in.window());
        }
        if (text.contains("Metaspace") || text.contains("Compressed class space")) {
            String max = in.settings().argument("-XX:MaxMetaspaceSize").orElse("not set");
            return "raise -XX:MaxMetaspaceSize (now " + max + ") or look for a class loader leak";
        }
        if (text.contains("Direct buffer memory")) {
            String max = in.settings().argument("-XX:MaxDirectMemorySize").orElse("not set");
            return "raise -XX:MaxDirectMemorySize (now " + max + ", default = max heap)";
        }
        if (text.contains("unable to create native thread")) {
            double live = in.window() == null ? 0d : in.window().total("threads.live.count").map(row -> row.avg()).orElse(0d);
            return "check the live thread count (" + HeapSizeAdvice.number(live) + ") and the pod memory limit "
                    + HeapSizeAdvice.bytes(in.settings().podMemoryLimitBytes());
        }
        return "see the OOM message: " + text;
    }

    private static boolean counts(EventRow event) {
        JsonNode fields = JsonFields.read(event.fields());
        String kind = fields == null ? "" : fields.path("kind").asText("").toLowerCase();
        String message = fields == null ? "" : fields.path("message").asText("");
        boolean metaspaceOnly = kind.contains("metaspace") && !kind.contains("heap") && !kind.contains("javaerror");
        if (!metaspaceOnly) {
            return true;
        }
        String lower = message.toLowerCase();
        return lower.contains("metaspace") || lower.contains("compressed class");
    }
}

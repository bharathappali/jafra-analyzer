package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.store.V2Store.EventRow;

/** Explicit System.gc() calls. Warning only. */
@ApplicationScoped
public class SystemGcCallsDetector implements Detector {
    @Override
    public String type() {
        return "system_gc_calls";
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
        if (in.settings().argument("-XX:+DisableExplicitGC").isPresent()
                || in.settings().raw().path("gc").path("explicit_gc_disabled").asBoolean(false)) {
            return WindowVerdict.ok(0d, "count", null, new Band(1d, null), family(), "explicit GC disabled", Set.of());
        }
        List<EventRow> calls = in.events().of("gc.system_gc");
        if (calls.isEmpty()) {
            return WindowVerdict.ok(0d, "count", null, new Band(1d, null), family(),
                    "band 1 call (proposal, pending KUSMA)", Set.of());
        }
        EventRow call = calls.get(0);
        String frame = call.topFrame() == null ? "unknown" : call.topFrame();
        return new WindowVerdict(
                State.WARNING,
                (double) calls.size(),
                "count",
                null,
                new Band(1d, null),
                family(),
                "band 1 call (proposal, pending KUSMA)",
                new Who("thread", call.thread() == null || call.thread().isBlank() ? "unknown" : call.thread(), null, false),
                List.of(new WhereFrame(frame, 100d, List.of("jdk.SystemGC"))),
                List.of("remove the System.gc() call at " + frame + ", or add -XX:+DisableExplicitGC"),
                "Application code asked for a full GC " + calls.size() + " times.",
                "System.gc() called",
                List.of("jdk.SystemGC"),
                Set.of(),
                null);
    }
}

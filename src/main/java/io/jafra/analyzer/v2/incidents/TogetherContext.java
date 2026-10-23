package io.jafra.analyzer.v2.incidents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.store.V2Store.IncidentRecord;

/** Mutable verdicts for this window, plus incidents already open on this JVM. */
public final class TogetherContext {
    private final Map<String, WindowVerdict> verdicts;
    private final List<IncidentRecord> openIncidents;

    public TogetherContext(Map<String, WindowVerdict> verdicts, List<IncidentRecord> openIncidents) {
        this.verdicts = new LinkedHashMap<>(verdicts);
        this.openIncidents = List.copyOf(openIncidents);
    }

    public WindowVerdict verdict(String type) {
        return verdicts.get(type);
    }

    public List<IncidentRecord> openIncidents() {
        return openIncidents;
    }

    public void replace(String type, WindowVerdict verdict) {
        if (type != null && verdict != null && verdicts.containsKey(type)) {
            verdicts.put(type, verdict);
        }
    }

    /** Raises WARNING to CRITICAL. Does nothing when the verdict is missing, OK, or UNKNOWN. */
    public void raiseCritical(String type, String todo, String reasonSuffix) {
        WindowVerdict current = verdicts.get(type);
        if (current == null || current.state() != State.WARNING) {
            return;
        }
        List<String> todoLines = new ArrayList<>();
        if (todo != null && !todo.isBlank()) {
            todoLines.add(todo);
        }
        todoLines.addAll(current.todo());
        String reason = current.reason() == null ? "" : current.reason();
        if (reasonSuffix != null && !reasonSuffix.isBlank()) {
            reason = reason.isBlank() ? reasonSuffix : reason + " " + reasonSuffix;
        }
        replace(type, new WindowVerdict(
                State.CRITICAL,
                current.value(),
                current.unit(),
                current.limit(),
                current.band(),
                current.family(),
                reason,
                current.who(),
                current.where(),
                todoLines,
                current.felt(),
                current.headline(),
                current.sourceEvents(),
                current.flags(),
                current.measuredAt(),
                current.openNow()));
    }

    public void prependTodo(String type, String todo) {
        WindowVerdict current = verdicts.get(type);
        if (current == null || todo == null || todo.isBlank() || current.state() == State.UNKNOWN || current.state() == State.OK) {
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add(todo);
        lines.addAll(current.todo());
        replace(type, copy(current, current.state(), lines, current.reason(), current.flags()));
    }

    public void appendReason(String type, String suffix) {
        WindowVerdict current = verdicts.get(type);
        if (current == null || suffix == null || suffix.isBlank()) {
            return;
        }
        String reason = current.reason() == null ? "" : current.reason();
        reason = reason.isBlank() ? suffix : reason + " " + suffix;
        replace(type, copy(current, current.state(), current.todo(), reason, current.flags()));
    }

    public void addFlag(String type, String flag) {
        WindowVerdict current = verdicts.get(type);
        if (current == null || flag == null) {
            return;
        }
        Set<String> flags = new LinkedHashSet<>(current.flags());
        flags.add(flag);
        replace(type, copy(current, current.state(), current.todo(), current.reason(), flags));
    }

    private WindowVerdict copy(WindowVerdict current, State state, List<String> todo, String reason, Set<String> flags) {
        return new WindowVerdict(
                state,
                current.value(),
                current.unit(),
                current.limit(),
                current.band(),
                current.family(),
                reason,
                current.who(),
                current.where(),
                todo,
                current.felt(),
                current.headline(),
                current.sourceEvents(),
                flags,
                current.measuredAt(),
                current.openNow());
    }
}

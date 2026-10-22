package io.jafra.analyzer.v2.incidents;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;

public record WindowVerdict(
        State state,
        Double value,
        String unit,
        Double limit,
        Band band,
        String family,
        String reason,
        Who who,
        List<WhereFrame> where,
        List<String> todo,
        String felt,
        String headline,
        List<String> sourceEvents,
        Set<String> flags,
        Long measuredAt) {

    public WindowVerdict {
        where = cap(where, 5);
        todo = cap(todo, 3);
        sourceEvents = sourceEvents == null ? List.of() : List.copyOf(sourceEvents);
        flags = flags == null ? Set.of() : Set.copyOf(flags);
    }

    public static WindowVerdict unknown(String reason, Set<String> flags) {
        return new WindowVerdict(
                State.UNKNOWN, null, null, null, null, null, reason, null, List.of(), List.of(), null, null, List.of(), flags, null);
    }

    public static WindowVerdict ok(
            Double value, String unit, Double limit, Band band, String family, String reason, Set<String> flags) {
        return new WindowVerdict(
                State.OK, value, unit, limit, band, family, reason, null, List.of(), List.of(), null, null, List.of(), flags, null);
    }

    private static <T> List<T> cap(List<T> values, int max) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() <= max) {
            return List.copyOf(values);
        }
        return List.copyOf(new ArrayList<>(values.subList(0, max)));
    }
}

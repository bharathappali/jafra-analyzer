package io.jafra.analyzer.v2.incidents;

import java.util.List;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;

public record Verdict(
        State state,
        double value,
        String unit,
        double warning,
        double critical,
        String headline,
        String felt,
        String who,
        List<String> todo,
        List<String> flags,
        String reason) {

    static Verdict unknown() {
        return new Verdict(State.UNKNOWN, Double.NaN, "", 0, 0, "", "", "", List.of(), List.of(), "");
    }

    static Verdict ok(double value, String unit, double warning, double critical) {
        return new Verdict(State.OK, value, unit, warning, critical, "", "", "", List.of(), List.of(), "");
    }
}

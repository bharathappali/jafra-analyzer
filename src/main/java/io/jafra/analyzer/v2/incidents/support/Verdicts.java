package io.jafra.analyzer.v2.incidents.support;

import java.util.List;
import java.util.Set;

import io.jafra.analyzer.v2.incidents.Band;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.WhereFrame;
import io.jafra.analyzer.v2.incidents.Who;
import io.jafra.analyzer.v2.incidents.WindowVerdict;

/** Builds a verdict and keeps the openNow flag explicit. */
public final class Verdicts {
    private Verdicts() {}

    public static WindowVerdict of(
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
            List<String> sources,
            Set<String> flags,
            boolean openNow) {
        return new WindowVerdict(
                state, value, unit, limit, band, family, reason, who, where, todo, felt, headline, sources, flags, null, openNow);
    }

    public static WindowVerdict withTodo(WindowVerdict verdict, List<String> todo) {
        return new WindowVerdict(
                verdict.state(),
                verdict.value(),
                verdict.unit(),
                verdict.limit(),
                verdict.band(),
                verdict.family(),
                verdict.reason(),
                verdict.who(),
                verdict.where(),
                todo,
                verdict.felt(),
                verdict.headline(),
                verdict.sourceEvents(),
                verdict.flags(),
                verdict.measuredAt(),
                verdict.openNow());
    }
}

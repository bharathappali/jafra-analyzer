package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

@ApplicationScoped
public class HeapNearFullDetector implements Detector {
    private final double warningPct;
    private final double criticalPct;

    @Inject
    public HeapNearFullDetector(
            @ConfigProperty(name = "jafra.v2.detect.heap-near-full.warning-pct", defaultValue = "85") double warningPct,
            @ConfigProperty(name = "jafra.v2.detect.heap-near-full.critical-pct", defaultValue = "95") double criticalPct) {
        this.warningPct = warningPct;
        this.criticalPct = criticalPct;
    }

    @Override
    public String type() {
        return "heap_near_full";
    }

    @Override
    public String group() {
        return "heap";
    }

    @Override
    public IncidentLifecycle.Kind kind() {
        return IncidentLifecycle.Kind.RATE_LEVEL;
    }

    @Override
    public String family() {
        return "A";
    }

    @Override
    public Set<Integer> jdks() {
        return Set.of(17, 21, 25);
    }

    @Override
    public WindowVerdict evaluate(WindowInput input) {
        Optional<RollupRow> row = input.window().total("heap.occupancy.pct");
        Double value = row.isPresent() && row.get().count() > 0 ? row.get().avg() : null;
        Long measuredAt = null;
        boolean carried = false;
        if (value == null) {
            Optional<RollupRow> prior = input.window().lastBefore("heap.occupancy.pct");
            if (prior.isEmpty()) {
                return WindowVerdict.unknown("no GC yet in this JVM", Set.of());
            }
            value = prior.get().avg();
            measuredAt = prior.get().ts();
            carried = true;
        }
        Band band = new Band(warningPct, criticalPct);
        Set<String> flags = carried ? Set.of("carried_forward") : Set.of();
        State state = value >= criticalPct ? State.CRITICAL : value >= warningPct ? State.WARNING : State.OK;
        String reason = "warning >= " + warningPct + "%; critical >= " + criticalPct + "% (proposal, pending KUSMA)";
        if (state == State.OK) {
            return new WindowVerdict(
                    State.OK, value, "pct", 100d, band, family(), reason, null, List.of(), List.of(), "", "", List.of(), flags, measuredAt);
        }
        String headline = "Heap stayed " + Math.round(value) + "% full after GC";
        String felt = "The heap stayed " + Math.round(value) + "% full even after GC, so GCs ran more often and freed less.";
        return new WindowVerdict(
                state,
                value,
                "pct",
                100d,
                band,
                family(),
                reason,
                new Who("class", "allocating class", null, true),
                List.of(),
                List.of("look at the top allocation site", "if it keeps rising after full GCs, check for a leak (live set trend)"),
                felt,
                headline,
                List.of(),
                flags,
                measuredAt);
    }
}

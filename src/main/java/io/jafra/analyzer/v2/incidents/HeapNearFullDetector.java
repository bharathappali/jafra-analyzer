package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

@ApplicationScoped
public class HeapNearFullDetector implements Detector {
    private final double warningPct;
    private final double criticalPct;
    private final ConcurrentHashMap<Integer, Double> lastOccupancy = new ConcurrentHashMap<>();

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
    public List<Integer> jdks() {
        return List.of(17, 21, 25);
    }

    @Override
    public Verdict evaluate(WindowInput input) {
        RollupRow row = input.totals().get("heap.occupancy.pct");
        boolean carried = false;
        Double value = row == null || row.count() == 0 ? null : row.avg();
        if (value == null) {
            value = lastOccupancy.get(input.jvm().jvmId());
            if (value == null) {
                return Verdict.unknown();
            }
            carried = true;
        } else {
            lastOccupancy.put(input.jvm().jvmId(), value);
        }
        State state = value >= criticalPct ? State.CRITICAL : value >= warningPct ? State.WARNING : State.OK;
        List<String> flags = carried ? List.of("carried_forward") : List.of();
        if (state == State.OK) {
            return new Verdict(State.OK, value, "pct", warningPct, criticalPct, "", "", "", List.of(), flags, "");
        }
        String headline = "Heap stayed " + Math.round(value) + "% full after GC";
        String felt = "The heap stayed " + Math.round(value) + "% full even after GC, so GCs ran more often and freed less.";
        String reason = "warning >= " + warningPct + "%; critical >= " + criticalPct + "% (proposal, pending KUSMA)";
        return new Verdict(
                state,
                value,
                "pct",
                warningPct,
                criticalPct,
                headline,
                felt,
                "allocating class",
                List.of("look at the top allocation site", "if it keeps rising after full GCs, check for a leak (live set trend)"),
                flags,
                reason);
    }
}

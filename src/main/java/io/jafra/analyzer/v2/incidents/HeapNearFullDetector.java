package io.jafra.analyzer.v2.incidents;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.jafra.analyzer.v2.api.V2Times;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

@ApplicationScoped
public class HeapNearFullDetector implements Detector {
    private final double warningPct;
    private final double criticalPct;
    private final HeapSizeAdvice advice;

    @Inject
    public HeapNearFullDetector(
            @ConfigProperty(name = "jafra.v2.detect.heap-near-full.warning-pct", defaultValue = "85") double warningPct,
            @ConfigProperty(name = "jafra.v2.detect.heap-near-full.critical-pct", defaultValue = "95") double criticalPct,
            HeapSizeAdvice advice) {
        this.warningPct = warningPct;
        this.criticalPct = criticalPct;
        this.advice = advice;
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
        Double value;
        Long measuredAt = null;
        boolean carried = false;
        if (row.isPresent() && row.get().count() > 0) {
            value = row.get().min();
        } else {
            Optional<RollupRow> prior = input.window().lastBefore("heap.occupancy.pct");
            if (prior.isEmpty() || prior.get().count() <= 0) {
                return WindowVerdict.unknown("no GC yet in this JVM", Set.of());
            }
            value = prior.get().min();
            measuredAt = prior.get().ts();
            carried = true;
        }
        List<WhereFrame> where = AllocSites.where(input);
        Who who = AllocSites.allocatingClass(input);
        boolean allocation = who != null || !where.isEmpty();
        Set<String> flags = new LinkedHashSet<>();
        if (carried) {
            flags.add("carried_forward");
        }
        if (allocation) {
            flags.add("estimate");
        }
        Band band = new Band(warningPct, criticalPct);
        State state = value >= criticalPct ? State.CRITICAL : value >= warningPct ? State.WARNING : State.OK;
        double bound = state == State.CRITICAL ? criticalPct : warningPct;
        String occupancy = HeapSizeAdvice.number(value);
        String reason = "band " + HeapSizeAdvice.number(warningPct) + "% / " + HeapSizeAdvice.number(criticalPct)
                + "% (proposal, pending KUSMA)";
        if (carried) {
            reason += "; carried forward from " + V2Times.text(measuredAt);
        }
        if (who == null) {
            reason += "; no allocation samples";
        }
        reason += "; flags read from jvmArguments; JAVA_TOOL_OPTIONS unverified";
        String site = where.isEmpty()
                ? "look at the top allocation site (no allocation samples)"
                : "look at the top allocation site " + where.get(0).frame() + " ("
                        + HeapSizeAdvice.number(where.get(0).sharePct()) + "% of sampled bytes)";
        List<String> sources = new ArrayList<>();
        sources.add("jdk.GCHeapSummary");
        sources.add("jdk.GCHeapConfiguration");
        if (allocation) {
            sources.addAll(AllocSites.SOURCE_EVENTS);
        }
        return new WindowVerdict(
                state,
                value,
                "pct",
                100d,
                band,
                family(),
                reason,
                who,
                where,
                List.of(
                        advice.todo(input.settings(), input.window()),
                        site,
                        "if it keeps rising after full GCs, check for a leak (live set trend)"),
                "The heap stayed " + occupancy + "% full even after GC, so GCs ran more often and freed less.",
                "Heap " + occupancy + "% full after GC vs the " + HeapSizeAdvice.number(bound) + "% band",
                sources,
                flags,
                measuredAt);
    }
}

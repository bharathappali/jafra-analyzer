package io.jafra.analyzer.v2.incidents;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

/** GC time and, when measured, GC CPU as a share of the pod limit. */
@ApplicationScoped
public class GcOverheadDetector implements Detector {
    private static final Set<String> CPU_COLLECTORS = Set.of(
            "G1New", "G1Old", "G1Full", "ParallelScavenge", "ParallelOld", "DefNew", "SerialOld");

    private final double warningPct;
    private final double criticalPct;
    private final HeapSizeAdvice advice;

    @Inject
    public GcOverheadDetector(DetectorBands bands, HeapSizeAdvice advice) {
        this(bands.gcOverhead().warningPct(), bands.gcOverhead().criticalPct(), advice);
    }

    public GcOverheadDetector(double warningPct, double criticalPct, HeapSizeAdvice advice) {
        this.warningPct = warningPct;
        this.criticalPct = criticalPct;
        this.advice = advice;
    }

    @Override
    public String type() {
        return "gc_overhead";
    }

    @Override
    public String group() {
        return "gc";
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
    public WindowVerdict evaluate(WindowInput in) {
        if (!jdks().contains(in.settings().jdk())) {
            return WindowVerdict.unknown("needs JDK " + jdks(), Set.of("not_on_this_jdk"));
        }
        Optional<RollupRow> pause = in.window().total(DetectorMetrics.GC_PAUSE_TIME_PCT);
        if (pause.isEmpty()) {
            return WindowVerdict.unknown("no gc.pause.time_pct row for this window", Set.of());
        }
        double pi = pause.get().avg();
        String collector = collector(in.settings());
        boolean cpuCollector = CPU_COLLECTORS.contains(in.settings().youngCollector())
                || CPU_COLLECTORS.contains(in.settings().oldCollector());
        boolean measured = cpuCollector && in.settings().declaredEventTypes().contains("jdk.GCCPUTime");
        Double cpuShare = null;
        if (measured) {
            Optional<RollupRow> gcCpu = in.window().total(DetectorMetrics.GC_CPU_PCT);
            Optional<RollupRow> limit = in.window().total(DetectorMetrics.CPU_CONTAINER_PCT_LIMIT);
            if (gcCpu.isPresent() && limit.isPresent()) {
                cpuShare = gcCpu.get().avg() * limit.get().avg() / 100d;
            }
        }
        double value = cpuShare == null ? pi : Math.max(pi, cpuShare);
        Band band = new Band(warningPct, criticalPct);
        CauseMix causes = causes(in);
        String whoName = collector + " · " + causes.top();
        Set<String> flags = new LinkedHashSet<>();
        List<WhereFrame> where = AllocSites.where(in);
        if (!where.isEmpty()) {
            flags.add("estimate");
        }
        List<String> sources = new ArrayList<>();
        sources.add("jdk.GarbageCollection");
        if (measured && cpuShare != null) {
            sources.add("jdk.GCCPUTime");
        }
        if (!where.isEmpty()) {
            sources.add("jdk.ObjectAllocationInNewTLAB");
            sources.add("jdk.ObjectAllocationOutsideTLAB");
        }
        if (value < warningPct) {
            return WindowVerdict.ok(value, "pct", null, band, family(), reason(warningPct, measured, collector, causes, cpuShare == null), flags);
        }
        State state = value >= criticalPct ? State.CRITICAL : State.WARNING;
        double bound = state == State.CRITICAL ? criticalPct : warningPct;
        String decided = reason(bound, measured, collector, causes, cpuShare == null);
        List<String> todo = new ArrayList<>();
        Double occupancy = occupancy(in);
        if (occupancy != null && occupancy > 70d) {
            todo.add(advice.todo(in.settings(), in.window()));
        } else if (!where.isEmpty()) {
            todo.add("cut allocation at " + where.get(0).frame() + " (" + where.get(0).sharePct() + "% of sampled bytes)");
        }
        String cpuText = cpuShare == null ? "n/a" : String.format(java.util.Locale.ROOT, "%.1f", cpuShare);
        todo.add("GC used " + String.format(java.util.Locale.ROOT, "%.1f", pi) + "% of wall time and " + cpuText
                + "% of the pod CPU limit");
        return new WindowVerdict(
                state,
                value,
                "pct",
                null,
                band,
                family(),
                decided,
                new Who("site", whoName, causes.share(), false),
                where,
                todo,
                "The JVM spent " + String.format(java.util.Locale.ROOT, "%.1f", value) + "% of the time on GC instead of running requests.",
                "GC overhead " + String.format(java.util.Locale.ROOT, "%.1f", value) + "%",
                sources,
                flags,
                null);
    }

    private static Double occupancy(WindowInput in) {
        Optional<RollupRow> row = in.window().total(DetectorMetrics.HEAP_OCCUPANCY_PCT);
        if (row.isPresent()) {
            return row.get().min();
        }
        return in.window().lastBefore(DetectorMetrics.HEAP_OCCUPANCY_PCT).map(RollupRow::min).orElse(null);
    }

    private static String collector(JvmSettings settings) {
        String young = settings.youngCollector();
        String old = settings.oldCollector();
        if (young.isBlank() && old.isBlank()) {
            return "unknown";
        }
        if (old.isBlank()) {
            return young;
        }
        if (young.isBlank()) {
            return old;
        }
        return young + "/" + old;
    }

    private static String reason(double bound, boolean measured, String collector, CauseMix causes, boolean cpuMissing) {
        String text = "band " + (int) bound + "% (proposal, pending KUSMA)";
        if (!measured) {
            text = text + " GC CPU not measured (" + collector + " / no jdk.GCCPUTime)";
        } else if (cpuMissing) {
            text = text + " no gc.cpu.pct or cpu.container.pct_limit row for this window";
        }
        if (!causes.detail().isBlank()) {
            text = text + " causes " + causes.detail();
        }
        return text;
    }

    private static CauseMix causes(WindowInput in) {
        List<RollupRow> rows = new ArrayList<>(in.window().breakdown(DetectorMetrics.GC_COUNT_PER_S, "cause"));
        rows.sort(java.util.Comparator.comparingDouble(RollupRow::sum).reversed());
        if (rows.isEmpty()) {
            return new CauseMix("unknown", null, "");
        }
        double total = 0d;
        for (RollupRow row : rows) {
            total += row.sum();
        }
        RollupRow top = rows.get(0);
        double share = total <= 0d ? 0d : 100d * top.sum() / total;
        StringBuilder detail = new StringBuilder();
        int shown = 0;
        for (RollupRow row : rows) {
            if (shown == 3) {
                break;
            }
            if (shown > 0) {
                detail.append(", ");
            }
            double part = total <= 0d ? 0d : 100d * row.sum() / total;
            detail.append(row.key()).append(' ').append((int) part).append('%');
            shown++;
        }
        return new CauseMix(top.key() + " " + (int) share + "%", share, detail.toString());
    }

    private record CauseMix(String top, Double share, String detail) {}
}

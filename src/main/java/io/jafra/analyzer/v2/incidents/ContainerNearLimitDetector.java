package io.jafra.analyzer.v2.incidents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.IncidentFlags;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.IncidentRecord;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

/** Pod memory near its limit. Heap-dominant advice raises the limit before shrinking the heap. */
@ApplicationScoped
public class ContainerNearLimitDetector implements Detector, PeerAware {
    private final double warningPct;
    private final double criticalPct;
    private final HeapSizeAdvice advice;

    @Inject
    public ContainerNearLimitDetector(DetectorBands bands, HeapSizeAdvice advice) {
        this(bands.containerNearLimit().warningPct(), bands.containerNearLimit().criticalPct(), advice);
    }

    public ContainerNearLimitDetector(double warningPct, double criticalPct, HeapSizeAdvice advice) {
        this.warningPct = warningPct;
        this.criticalPct = criticalPct;
        this.advice = advice;
    }

    @Override
    public String type() {
        return "container_near_limit";
    }

    @Override
    public String group() {
        return "container";
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
        WindowVerdict gate = DetectorSupport.jdkGate(this, in);
        if (gate != null) {
            return gate;
        }
        if (in.window() == null) {
            return WindowVerdict.unknown("no pod memory limit", Set.of(IncidentFlags.no_memory_limit.name()));
        }
        Double limit = in.window().total(DetectorMetrics.CONTAINER_LIMIT).map(RollupRow::avg).orElse(in.settings().podMemoryLimitBytes());
        if (limit == null || limit <= 0d) {
            return WindowVerdict.unknown("no pod memory limit", Set.of(IncidentFlags.no_memory_limit.name()));
        }
        var usage = in.window().total(DetectorMetrics.CONTAINER_USAGE_PCT);
        if (usage.isEmpty() || usage.get().count() <= 0) {
            return WindowVerdict.unknown("no " + DetectorMetrics.CONTAINER_USAGE_PCT + " row", Set.of(IncidentFlags.page_cache_included.name()));
        }
        double q = usage.get().max();
        double fails = in.window().total(DetectorMetrics.CONTAINER_MEM_FAIL).map(RollupRow::sum).orElse(0d);
        State state = State.OK;
        if (q >= criticalPct || fails > 0d) {
            state = State.CRITICAL;
        } else if (q >= warningPct) {
            state = State.WARNING;
        }
        String part = dominant(in);
        List<WhereFrame> where = malloc(in);
        String reason = DetectorSupport.band(HeapSizeAdvice.number(warningPct) + "% / " + HeapSizeAdvice.number(criticalPct) + "%");
        if (in.settings().jdk() == 17) {
            reason += "; no RSS on JDK 17, split is heap versus the rest";
        }
        if (where.isEmpty()) {
            reason += "; no native allocation samples (async-profiler nativemem off)";
        }
        reason += "; flags read from jvmArguments; JAVA_TOOL_OPTIONS unverified";
        return Verdicts.of(
                state,
                q,
                "pct",
                100d,
                new Band(warningPct, criticalPct),
                family(),
                reason,
                new Who("interface", part, null, false),
                where,
                todos(in, part),
                "The pod is using " + HeapSizeAdvice.number(q) + "% of its memory limit; near 100% it gets OOM-killed.",
                "Pod memory " + HeapSizeAdvice.number(q) + "% of the limit",
                List.of("jdk.ContainerMemoryUsage", "jdk.ContainerConfiguration"),
                Set.of(IncidentFlags.page_cache_included.name()),
                false);
    }

    @Override
    public WindowVerdict adjust(WindowVerdict self, Map<String, WindowVerdict> verdicts, List<IncidentRecord> open) {
        if (self == null || !heapOpen(verdicts, open)) {
            return self;
        }
        List<String> kept = new ArrayList<>();
        for (String line : self.todo()) {
            if (!line.startsWith("or lower")) {
                kept.add(line);
            }
        }
        return Verdicts.withTodo(self, kept);
    }

    private List<String> todos(WindowInput in, String part) {
        Double limit = in.window().total(DetectorMetrics.CONTAINER_LIMIT).map(RollupRow::avg).orElse(in.settings().podMemoryLimitBytes());
        if ("heap".equals(part)) {
            String percent = in.settings().argument("-XX:MaxRAMPercentage").orElse("25");
            boolean xmx = in.settings().argument("-Xmx").isPresent() || in.settings().argument("-XX:MaxHeapSize").isPresent();
            String shrink = xmx
                    ? "or lower -Xmx from " + HeapSizeAdvice.bytes(in.settings().heapMaxBytes())
                    : "or lower -XX:MaxRAMPercentage from " + percent + "%";
            return List.of("raise the pod memory limit from " + HeapSizeAdvice.bytes(limit), shrink);
        }
        if ("native".equals(part) || part.startsWith("native ")) {
            if (in.settings().raw().path("nmt").asText("").equals("off")) {
                return List.of("enable NMT with the pod annotation jafra.io/jdk-nmt: \"enabled\"");
            }
            return List.of("look at NMT category " + part);
        }
        return List.of("usage includes page cache; check memory.stat before raising the limit");
    }

    private static String dominant(WindowInput in) {
        double heap = in.window().total(DetectorMetrics.HEAP_COMMITTED_BYTES).map(RollupRow::avg).orElse(0d);
        double usage = in.window().total(DetectorMetrics.CONTAINER_USAGE).map(RollupRow::avg).orElse(0d);
        if (in.settings().jdk() == 17) {
            double rest = Math.max(0d, usage - heap);
            return heap >= rest ? "heap" : "other";
        }
        double rss = in.window().total(DetectorMetrics.CONTAINER_RSS).map(RollupRow::avg).orElse(0d);
        double nativeBytes = Math.max(0d, rss - heap);
        double other = Math.max(0d, usage - rss);
        if (heap >= nativeBytes && heap >= other) {
            return "heap";
        }
        if (nativeBytes >= other) {
            List<RollupRow> categories = in.window().breakdown(DetectorMetrics.NMT_CATEGORY, "category");
            if (!categories.isEmpty()) {
                return "native " + categories.get(0).key() + " (" + HeapSizeAdvice.bytes(categories.get(0).sum()) + ")";
            }
            return "native";
        }
        return "other";
    }

    private static List<WhereFrame> malloc(WindowInput in) {
        if (in.samples() == null) {
            return List.of();
        }
        return AllocSites.where(in);
    }

    private static boolean heapOpen(Map<String, WindowVerdict> verdicts, List<IncidentRecord> open) {
        WindowVerdict heap = verdicts == null ? null : verdicts.get("heap_near_full");
        if (heap != null && (heap.state() == State.WARNING || heap.state() == State.CRITICAL)) {
            return true;
        }
        if (open == null) {
            return false;
        }
        for (IncidentRecord row : open) {
            if ("heap_near_full".equals(row.type()) && "open".equals(row.status())) {
                return true;
            }
        }
        return false;
    }
}

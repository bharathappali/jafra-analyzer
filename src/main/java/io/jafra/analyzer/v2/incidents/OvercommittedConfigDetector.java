package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.IncidentFlags;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

/** Heap max plus non-heap headroom exceeds the pod memory limit. Repeats until that config changes. */
@ApplicationScoped
public class OvercommittedConfigDetector implements Detector {
    private final double heapFactor;
    private final HeapSizeAdvice advice;
    private final ConcurrentHashMap<Integer, Held> held = new ConcurrentHashMap<>();

    @Inject
    public OvercommittedConfigDetector(DetectorBands bands, HeapSizeAdvice advice) {
        this(bands.overcommittedConfig().heapFactor(), advice);
    }

    public OvercommittedConfigDetector(double heapFactor, HeapSizeAdvice advice) {
        this.heapFactor = heapFactor;
        this.advice = advice;
    }

    @Override
    public String type() {
        return "overcommitted_config";
    }

    @Override
    public String group() {
        return "container";
    }

    @Override
    public IncidentLifecycle.Kind kind() {
        return IncidentLifecycle.Kind.INSTANT;
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
        Double heap = in.window().total(DetectorMetrics.HEAP_MAX_BYTES).map(RollupRow::avg).orElse(in.settings().heapMaxBytes());
        Double limit = in.window().total(DetectorMetrics.CONTAINER_LIMIT).map(RollupRow::avg).orElse(in.settings().podMemoryLimitBytes());
        if (limit == null || limit <= 0d) {
            return WindowVerdict.unknown("no pod memory limit", Set.of(IncidentFlags.no_memory_limit.name()));
        }
        if (heap == null || heap <= 0d) {
            return WindowVerdict.unknown("no " + DetectorMetrics.HEAP_MAX_BYTES + " row", Set.of());
        }
        int jvmId = in.jvm() == null ? 0 : in.jvm().jvmId();
        Held previous = held.get(jvmId);
        if (previous != null && previous.heap() == heap && previous.limit() == limit) {
            return previous.verdict();
        }
        boolean over = heap * heapFactor > limit;
        State state = over ? State.WARNING : State.OK;
        WindowVerdict verdict = Verdicts.of(
                state,
                heap,
                "bytes",
                limit,
                new Band(limit / heapFactor, null),
                family(),
                DetectorSupport.band("heap × " + HeapSizeAdvice.number(heapFactor) + " > pod limit")
                        + "; configuration, no code path; flags read from jvmArguments; JAVA_TOOL_OPTIONS unverified",
                over ? new Who("site", "JVM config", null, false) : null,
                List.of(),
                over ? advice.lower(in.settings(), in.window()) : List.of(),
                "The heap can grow larger than the pod's memory allows once non-heap memory is added.",
                "Heap " + HeapSizeAdvice.bytes(heap) + " × " + HeapSizeAdvice.number(heapFactor) + " versus pod limit " + HeapSizeAdvice.bytes(limit),
                List.of("jdk.GCHeapConfiguration", "jdk.ContainerConfiguration"),
                Set.of(),
                false);
        held.put(jvmId, new Held(heap, limit, verdict));
        return verdict;
    }

    private record Held(double heap, double limit, WindowVerdict verdict) {}
}

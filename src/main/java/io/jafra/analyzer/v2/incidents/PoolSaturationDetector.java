package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.IncidentFlags;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.IncidentRecord;

/** Request threads all busy. Critical only via together rule L1. */
@ApplicationScoped
public class PoolSaturationDetector implements Detector, PeerAware {
    private final double warningPct;
    private final int minSamples;
    private final List<String> prefixes;

    @Inject
    public PoolSaturationDetector(DetectorBands bands) {
        this(bands.poolSaturation().warningPct(), bands.poolSaturation().minSamples(), List.of(bands.poolSaturation().prefixes().split(",")));
    }

    public PoolSaturationDetector(double warningPct, int minSamples, List<String> prefixes) {
        this.warningPct = warningPct;
        this.minSamples = minSamples;
        this.prefixes = prefixes;
    }

    @Override
    public String type() {
        return "pool_saturation";
    }

    @Override
    public String group() {
        return "threads";
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
        if (in.samples() == null) {
            return WindowVerdict.unknown("no wall-clock samples for request pools", Set.of());
        }
        Map<String, Integer> all = new java.util.LinkedHashMap<>();
        Map<String, Integer> busy = new java.util.LinkedHashMap<>();
        Map<String, Integer> waiting = new java.util.LinkedHashMap<>();
        for (var sample : in.samples().rows("wall")) {
            String pool = pool(sample.thread());
            if (pool == null) {
                continue;
            }
            all.merge(pool, 1, Integer::sum);
            boolean idle = "waiting".equals(sample.state()) || "parked".equals(sample.state());
            if (idle) {
                waiting.merge(pool, 1, Integer::sum);
            } else {
                busy.merge(pool, 1, Integer::sum);
            }
        }
        String worst = null;
        double share = 0d;
        for (Map.Entry<String, Integer> entry : all.entrySet()) {
            if (entry.getValue() < minSamples) {
                continue;
            }
            double b = 100d * busy.getOrDefault(entry.getKey(), 0) / entry.getValue();
            if (worst == null || b > share) {
                worst = entry.getKey();
                share = b;
            }
        }
        if (worst == null) {
            return WindowVerdict.unknown("no wall-clock samples for request pools", Set.of());
        }
        State state = share >= warningPct ? State.WARNING : State.OK;
        int threads = all.getOrDefault(worst, 0);
        double waitShare = 100d * waiting.getOrDefault(worst, 0) / Math.max(1, all.get(worst));
        String todo = waitShare > 50d
                ? "threads are waiting: fix what they wait on (unknown)"
                : "threads are running: enlarge the pool (" + worst + ")";
        return Verdicts.of(
                state,
                share,
                "pct",
                100d,
                new Band(warningPct, null),
                family(),
                DetectorSupport.band(HeapSizeAdvice.number(warningPct) + "% busy"),
                new Who("thread", worst + " (" + threads + " threads)", share, true),
                List.of(),
                List.of(todo),
                "All " + worst + " threads were busy " + HeapSizeAdvice.number(share) + "% of the time; new requests queue.",
                worst + " busy " + HeapSizeAdvice.number(share) + "%",
                List.of("jdk.ExecutionSample"),
                Set.of("estimate"),
                false);
    }

    @Override
    public WindowVerdict adjust(WindowVerdict self, Map<String, WindowVerdict> verdicts, List<IncidentRecord> open) {
        if (self == null || self.state() != State.WARNING) {
            return self;
        }
        String cpu = cpuIncident(verdicts, open);
        if (cpu == null) {
            return self;
        }
        List<String> todo = new java.util.ArrayList<>();
        todo.add("threads are running but CPU-bound: fix CPU first (" + cpu + "), a bigger pool makes it worse");
        for (String line : self.todo()) {
            if (!line.startsWith("threads are running: enlarge")) {
                todo.add(line);
            }
        }
        return Verdicts.withTodo(self, todo);
    }

    private String pool(String thread) {
        if (thread == null) {
            return null;
        }
        for (String prefix : prefixes) {
            if (!prefix.isBlank() && thread.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    private static String cpuIncident(Map<String, WindowVerdict> verdicts, List<IncidentRecord> open) {
        for (String type : List.of("cpu_throttling", "cpu_saturation")) {
            WindowVerdict verdict = verdicts == null ? null : verdicts.get(type);
            if (verdict != null && (verdict.state() == State.WARNING || verdict.state() == State.CRITICAL)) {
                return type;
            }
            if (open != null) {
                for (IncidentRecord row : open) {
                    if (type.equals(row.type()) && "open".equals(row.status())) {
                        return row.id();
                    }
                }
            }
        }
        return null;
    }
}

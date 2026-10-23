package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorHistory;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.RobustZ;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

/** One peer's slow socket time. Baselines are per peer. Writes count in the baseline key. */
@ApplicationScoped
public class SlowPeerDetector implements Detector {
    private final double warning;
    private final double critical;
    private final double zMin;
    private final DetectorHistory history;

    @Inject
    public SlowPeerDetector(DetectorBands bands, DetectorHistory history) {
        this(bands.slowPeer().warningThreadS(), bands.slowPeer().criticalThreadS(), bands.slowPeer().z(), history);
    }

    public SlowPeerDetector(double warning, double critical, double zMin, DetectorHistory history) {
        this.warning = warning;
        this.critical = critical;
        this.zMin = zMin;
        this.history = history;
    }

    @Override
    public String type() { return "slow_peer"; }
    @Override
    public String group() { return "socket"; }
    @Override
    public IncidentLifecycle.Kind kind() { return IncidentLifecycle.Kind.RATE_LEVEL; }
    @Override
    public String family() { return "A"; }
    @Override
    public Set<Integer> jdks() { return Set.of(17, 21, 25); }

    @Override
    public WindowVerdict evaluate(WindowInput in) {
        WindowVerdict gate = DetectorSupport.jdkGate(this, in);
        if (gate != null) return gate;
        if (in.window() == null) return WindowVerdict.unknown("no socket rows", Set.of("lower_bound"));
        List<RollupRow> reads = in.window().breakdown(DetectorMetrics.SOCKET_READ, "peer");
        List<RollupRow> writes = in.window().breakdown(DetectorMetrics.SOCKET_WRITE, "peer");
        if (reads.isEmpty() && writes.isEmpty()) {
            return WindowVerdict.unknown("no socket rows", Set.of("lower_bound"));
        }
        double seconds = Math.max(0.001d, (in.toMs() - in.fromMs()) / 1000d);
        String peer = !reads.isEmpty() ? reads.get(0).key() : writes.get(0).key();
        double micros = sum(reads, peer) + sum(writes, peer);
        double value = (micros / 1_000_000d) / seconds;
        if (history != null) history.record(in.jvm().jvmId(), DetectorMetrics.SOCKET_READ_TIME, peer, in.fromMs(), value);
        Baseline baseline = in.baselines() == null ? null : in.baselines().of(DetectorMetrics.SOCKET_READ_TIME, peer, "socket", "avg");
        int n = baseline == null ? 0 : baseline.n();
        if (value < critical && (baseline == null || baseline.median() == null || n < RobustZ.WARMUP)) {
            return WindowVerdict.unknown("baseline warming up (" + n + "/12)", Set.of("lower_bound"));
        }
        double z = baseline == null || baseline.median() == null || baseline.mad() == null ? 0d
                : RobustZ.z(value, baseline.median(), baseline.mad(), Math.max(n, RobustZ.WARMUP));
        State state = State.OK;
        if (value >= critical) state = State.CRITICAL;
        else if (value >= warning && z >= zMin) state = State.WARNING;
        return Verdicts.of(state, value, "thread_s_per_s", null, new Band(warning, critical), family(),
                DetectorSupport.band(warning + " / " + critical + " thread-s/s") + "; per-peer baseline " + peer,
                new Who("interface", peer, 100d, false), List.of(),
                List.of("look at " + peer + " (database / HTTP service / Kafka)"),
                "Calls to " + peer + " spent " + HeapSizeAdvice.number(value) + " thread-seconds per second in slow reads/writes.",
                "Slow peer " + peer,
                List.of("jdk.SocketRead", "jdk.SocketWrite"), Set.of("lower_bound"), false);
    }

    private static double sum(List<RollupRow> rows, String key) {
        for (RollupRow row : rows) if (key.equals(row.key())) return row.sum();
        return 0d;
    }
}

package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

/** TLS handshakes to one peer. Warning only. */
@ApplicationScoped
public class TlsChurnDetector implements Detector {
    private final double perS;

    @Inject
    public TlsChurnDetector(DetectorBands bands) {
        this(bands.tlsChurn().perS());
    }

    public TlsChurnDetector(double perS) {
        this.perS = perS;
    }

    @Override
    public String type() { return "tls_churn"; }
    @Override
    public String group() { return "socket"; }
    @Override
    public IncidentLifecycle.Kind kind() { return IncidentLifecycle.Kind.RATE_LEVEL; }
    @Override
    public String family() { return "B"; }
    @Override
    public Set<Integer> jdks() { return Set.of(17, 21, 25); }

    @Override
    public WindowVerdict evaluate(WindowInput in) {
        WindowVerdict gate = DetectorSupport.jdkGate(this, in);
        if (gate != null) return gate;
        if (in.window() == null) return WindowVerdict.unknown("no " + DetectorMetrics.SOCKET_TLS + " row", Set.of("lower_bound"));
        List<RollupRow> peers = in.window().breakdown(DetectorMetrics.SOCKET_TLS, "peer");
        if (peers.isEmpty()) return WindowVerdict.unknown("no " + DetectorMetrics.SOCKET_TLS + " row", Set.of("lower_bound"));
        RollupRow top = peers.get(0);
        State state = top.avg() >= perS ? State.WARNING : State.OK;
        return Verdicts.of(state, top.avg(), "per_s", null, new Band(perS, null), family(),
                DetectorSupport.band(HeapSizeAdvice.number(perS) + "/s"),
                new Who("interface", top.key(), null, false), List.of(),
                List.of("reuse connections to " + top.key() + " (connection pool / keep-alive) instead of a new TLS handshake per call"),
                "The app opens " + HeapSizeAdvice.number(top.avg()) + " new TLS connections per second to " + top.key() + ".",
                "TLS handshakes " + HeapSizeAdvice.number(top.avg()) + "/s",
                List.of("jdk.TLSHandshake"), Set.of("lower_bound"), false);
    }
}

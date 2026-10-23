package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorHistory;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.IncidentFlags;
import io.jafra.analyzer.v2.incidents.support.InternalInputs;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.trend.TrendService;

/** Direct buffer use rising toward its limit. */
@ApplicationScoped
public class DirectBufferLeakDetector implements Detector {
    private final double warningPct;
    private final double criticalPct;
    private final DetectorHistory history;

    @Inject
    public DirectBufferLeakDetector(DetectorBands bands, DetectorHistory history) {
        this(bands.directBufferLeak().warningPct(), bands.directBufferLeak().criticalPct(), history);
    }

    public DirectBufferLeakDetector(double warningPct, double criticalPct, DetectorHistory history) {
        this.warningPct = warningPct;
        this.criticalPct = criticalPct;
        this.history = history;
    }

    @Override
    public String type() {
        return "direct_buffer_leak";
    }

    @Override
    public String group() {
        return "nmt";
    }

    @Override
    public IncidentLifecycle.Kind kind() {
        return IncidentLifecycle.Kind.RATE_LEVEL;
    }

    @Override
    public String family() {
        return "C";
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
        if (history == null || in.window() == null) {
            return WindowVerdict.unknown("not enough data yet (0/30)", Set.of(IncidentFlags.netty_offheap_excluded.name()));
        }
        Double used = in.window().total(DetectorMetrics.NMT_DIRECT_USED).map(row -> row.avg()).orElse(null);
        if (used == null) {
            return WindowVerdict.unknown("no " + DetectorMetrics.NMT_DIRECT_USED + " row", Set.of(IncidentFlags.netty_offheap_excluded.name()));
        }
        Limit limit = limit(in);
        if (limit.bytes() == null || limit.bytes() <= 0d) {
            return WindowVerdict.unknown("no direct buffer limit", Set.of(IncidentFlags.netty_offheap_excluded.name()));
        }
        List<DetectorHistory.Point> points = history.points(
                in.jvm().jvmId(), DetectorMetrics.NMT_DIRECT_USED, "", "avg", in.toMs() - 30L * 60_000L, in.toMs() + 1);
        if (points.size() < 30) {
            return WindowVerdict.unknown("not enough data yet (" + points.size() + "/30)", Set.of(IncidentFlags.netty_offheap_excluded.name()));
        }
        long[] times = new long[points.size()];
        double[] values = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            times[i] = points.get(i).ts();
            values[i] = points.get(i).value();
        }
        TrendService.Analysis analysis = TrendService.analyze(times, values, limit.bytes());
        if (!analysis.known()) {
            return WindowVerdict.unknown(analysis.reason(), Set.of(IncidentFlags.netty_offheap_excluded.name()));
        }
        double pct = 100d * used / limit.bytes();
        boolean rising = analysis.result().significant() && analysis.result().slopePerHour() > 0d;
        State state = State.OK;
        if (rising && pct >= criticalPct) {
            state = State.CRITICAL;
        } else if (rising && pct >= warningPct) {
            state = State.WARNING;
        }
        double buffers = in.window().total(DetectorMetrics.NMT_DIRECT_BUFFERS).map(row -> row.avg()).orElse(0d);
        String reason = DetectorSupport.band(HeapSizeAdvice.number(warningPct) + "% / " + HeapSizeAdvice.number(criticalPct) + "%");
        reason += "; limit from " + limit.source();
        if (in.samples() == null || in.samples().rows("malloc").isEmpty()) {
            reason += "; no native allocation samples (async-profiler nativemem off)";
        }
        String flag = in.settings().argument("-XX:MaxDirectMemorySize").orElse("not set (= max heap " + HeapSizeAdvice.bytes(in.settings().heapMaxBytes()) + ")");
        return Verdicts.of(
                state,
                pct,
                "pct",
                100d,
                new Band(warningPct, criticalPct),
                family(),
                reason,
                new Who("site", HeapSizeAdvice.number(buffers) + " buffers, " + HeapSizeAdvice.bytes(used) + " used of " + HeapSizeAdvice.bytes(limit.bytes()), null, false),
                List.of(),
                List.of("release or pool the direct buffers", "raise -XX:MaxDirectMemorySize from " + flag),
                "Direct (off-heap) buffer memory keeps growing toward its " + HeapSizeAdvice.bytes(limit.bytes()) + " limit.",
                "Direct buffers " + HeapSizeAdvice.number(pct) + "% of " + limit.source(),
                List.of("jdk.DirectBufferStatistics"),
                Set.of(IncidentFlags.netty_offheap_excluded.name()),
                false);
    }

    private static Limit limit(WindowInput in) {
        List<DetectorHistory.Point> max = List.of();
        Double internal = null;
        if (in.jvm() != null) {
            // The current window's maxCapacity is also a total when the extractor wrote it as a metric-like series.
        }
        var row = in.window().total(InternalInputs.DIRECT_MAX_BYTES);
        if (row.isPresent() && row.get().avg() > 0d) {
            return new Limit(row.get().avg(), "internal.direct.max.bytes");
        }
        String flag = in.settings().argument("-XX:MaxDirectMemorySize").orElse(null);
        Double parsed = parse(flag);
        if (parsed != null) {
            return new Limit(parsed, "-XX:MaxDirectMemorySize");
        }
        Double heap = in.window().total(DetectorMetrics.HEAP_MAX_BYTES).map(item -> item.avg()).orElse(in.settings().heapMaxBytes());
        return new Limit(heap, "heap max");
    }

    private static Double parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String text = raw.trim().toLowerCase();
            double factor = 1d;
            if (text.endsWith("m")) {
                factor = 1_048_576d;
                text = text.substring(0, text.length() - 1);
            } else if (text.endsWith("g")) {
                factor = 1_073_741_824d;
                text = text.substring(0, text.length() - 1);
            }
            return Double.parseDouble(text) * factor;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private record Limit(Double bytes, String source) {}
}

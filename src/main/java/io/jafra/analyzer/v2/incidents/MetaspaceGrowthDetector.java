package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorHistory;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.EventRow;
import io.jafra.analyzer.v2.trend.TrendMath;
import io.jafra.analyzer.v2.trend.TrendService;

/** Metaspace rising toward its limit, or a metaspace allocation failure. */
@ApplicationScoped
public class MetaspaceGrowthDetector implements Detector {
    private final double warningHeadroomMin;
    private final DetectorHistory history;

    @Inject
    public MetaspaceGrowthDetector(DetectorBands bands, DetectorHistory history) {
        this(bands.metaspaceGrowth().warningHeadroomMin(), history);
    }

    public MetaspaceGrowthDetector(double warningHeadroomMin, DetectorHistory history) {
        this.warningHeadroomMin = warningHeadroomMin;
        this.history = history;
    }

    @Override
    public String type() {
        return "metaspace_growth";
    }

    @Override
    public String group() {
        return "metaspace";
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
        EventRow failure = failure(in);
        if (failure != null) {
            JsonNode fields = JsonFields.read(failure.fields());
            String loader = fields == null ? "unknown" : fields.path("loader").asText("unknown");
            String frame = failure.topFrame() == null ? "unknown" : failure.topFrame();
            String max = in.settings().argument("-XX:MaxMetaspaceSize").orElse("not set");
            return Verdicts.of(
                    State.CRITICAL,
                    1d,
                    "count",
                    null,
                    new Band(warningHeadroomMin, 0d),
                    family(),
                    DetectorSupport.band("metaspace allocation failure") + "; flags read from jvmArguments; JAVA_TOOL_OPTIONS unverified",
                    new Who("loader", loader, null, false),
                    List.of(new WhereFrame(frame, 100d, List.of(failure.type()))),
                    List.of("raise -XX:MaxMetaspaceSize from " + max, "look at what keeps creating class loaders (" + loader + ")"),
                    "Class metadata keeps growing; at this rate it hits " + max + " in about 0 min.",
                    "Metaspace allocation failed",
                    List.of("jdk.MetaspaceAllocationFailure", "jdk.MetaspaceOOM"),
                    Set.of(),
                    true);
        }
        if (history == null) {
            return WindowVerdict.unknown("not enough data yet (0/30)", Set.of());
        }
        List<DetectorHistory.Point> points = history.points(
                in.jvm().jvmId(), DetectorMetrics.METASPACE_USED, "", "avg", in.toMs() - 60L * 60_000L, in.toMs() + 1);
        long[] times = new long[points.size()];
        double[] values = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            times[i] = points.get(i).ts();
            values[i] = points.get(i).value();
        }
        Double limit = parseBytes(in.settings().argument("-XX:MaxMetaspaceSize").orElse(null));
        TrendService.Analysis analysis = TrendService.analyze(times, values, limit);
        if (!analysis.known()) {
            return WindowVerdict.unknown(analysis.reason(), Set.of());
        }
        TrendMath.Result trend = analysis.result();
        double headroom = limit == null
                ? DetectorSupport.headroom(in.settings().podMemoryLimitBytes(), latest(in, DetectorMetrics.CONTAINER_USAGE), trend.slopePerHour()).minutes() == null
                        ? Double.POSITIVE_INFINITY
                        : DetectorSupport.headroom(in.settings().podMemoryLimitBytes(), latest(in, DetectorMetrics.CONTAINER_USAGE), trend.slopePerHour()).minutes()
                : trend.timeToLimitMin() == null ? Double.POSITIVE_INFINITY : trend.timeToLimitMin().mid();
        boolean rising = trend.significant() && trend.slopePerHour() > 0d && !"plateau".equals(trend.shape());
        State state = rising && headroom < warningHeadroomMin ? State.WARNING : State.OK;
        String max = in.settings().argument("-XX:MaxMetaspaceSize").orElse("not set");
        String reason = DetectorSupport.band(HeapSizeAdvice.number(warningHeadroomMin) + " min headroom");
        if (limit == null) {
            reason += "; no MaxMetaspaceSize, using pod-limit headroom";
        }
        reason += "; no stack for gradual growth";
        return Verdicts.of(
                state,
                headroom,
                "min",
                limit,
                new Band(warningHeadroomMin, null),
                family(),
                reason,
                new Who("loader", "unknown", null, false),
                List.of(),
                List.of("raise -XX:MaxMetaspaceSize from " + max, "look at what keeps creating class loaders (unknown)"),
                "Class metadata keeps growing; at this rate it hits " + max + " in about " + HeapSizeAdvice.number(headroom) + " min.",
                "Metaspace headroom " + HeapSizeAdvice.number(headroom) + " min",
                List.of("jdk.MetaspaceSummary"),
                Set.of(),
                false);
    }

    private static EventRow failure(WindowInput in) {
        if (in.events() == null) {
            return null;
        }
        List<EventRow> failures = in.events().of("metaspace.alloc_failure");
        if (!failures.isEmpty()) {
            return failures.get(0);
        }
        for (EventRow event : in.events().of("oom")) {
            JsonNode fields = JsonFields.read(event.fields());
            String kind = fields == null ? "" : fields.path("kind").asText("").toLowerCase();
            if (kind.contains("metaspace")) {
                return event;
            }
        }
        return null;
    }

    private static Double latest(WindowInput in, String metric) {
        if (in.window() == null) {
            return null;
        }
        return in.window().total(metric).map(row -> row.avg()).orElse(null);
    }

    private static Double parseBytes(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim().toLowerCase();
        double factor = 1d;
        if (text.endsWith("g")) {
            factor = 1_073_741_824d;
            text = text.substring(0, text.length() - 1);
        } else if (text.endsWith("m")) {
            factor = 1_048_576d;
            text = text.substring(0, text.length() - 1);
        }
        try {
            return Double.parseDouble(text) * factor;
        } catch (NumberFormatException error) {
            return null;
        }
    }
}

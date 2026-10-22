package io.jafra.analyzer.v2.incidents;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.store.V2Store.EventRow;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

@ApplicationScoped
public class LongPauseDetector implements Detector {
    private final double warningMs;
    private final double criticalMs;
    private final ObjectMapper json = new ObjectMapper();

    @Inject
    public LongPauseDetector(
            @ConfigProperty(name = "jafra.v2.detect.long-pause.warning-ms", defaultValue = "200") double warningMs,
            @ConfigProperty(name = "jafra.v2.detect.long-pause.critical-ms", defaultValue = "1000") double criticalMs) {
        this.warningMs = warningMs;
        this.criticalMs = criticalMs;
    }

    @Override
    public String type() {
        return "long_pause";
    }

    @Override
    public String group() {
        return "gc";
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
    public WindowVerdict evaluate(WindowInput input) {
        Optional<RollupRow> pause = input.window().total("gc.pause.us");
        if (pause.isEmpty() || pause.get().count() == 0) {
            return WindowVerdict.ok(0d, "ms", null, new Band(warningMs, criticalMs), family(), "", Set.of());
        }
        double valueMs = pause.get().max() / 1000d;
        double warning = warningMs;
        Double pauseTarget = pauseTarget(input);
        if (pauseTarget != null && pauseTarget > 0d) {
            warning = Math.max(warning, pauseTarget);
        }
        Baseline baseline = input.baselines().of("gc.pause.us", "", group(), "max");
        if (baseline.n() >= 60 && baseline.events() >= 50 && baseline.p99() != null) {
            warning = Math.max(warning, 3d * baseline.p99() / 1000d);
        }
        Band band = new Band(warning, criticalMs);
        State state = valueMs >= criticalMs ? State.CRITICAL : valueMs >= warning ? State.WARNING : State.OK;
        if (state == State.OK) {
            return WindowVerdict.ok(valueMs, "ms", null, band, family(), "", Set.of());
        }
        String cause = "GC";
        String collector = "collector";
        for (EventRow event : input.events().of("gc.pause")) {
            if (event.fields() == null) {
                continue;
            }
            try {
                var node = json.readTree(event.fields());
                if (node.path("cause").asText(null) != null) {
                    cause = node.path("cause").asText();
                }
                if (node.path("collector").asText(null) != null) {
                    collector = node.path("collector").asText();
                }
            } catch (Exception ignored) {
                // a pause row without JSON still leaves the generic headline
            }
        }
        String headline = collector + " " + cause + " paused for " + Math.round(valueMs) + " ms";
        List<String> todo = new ArrayList<>();
        if ("System.gc()".equals(cause)) {
            todo.add("remove the System.gc() call or -XX:+DisableExplicitGC");
        } else if (cause.contains("Humongous")) {
            todo.add("raise -XX:G1HeapRegionSize");
        } else {
            todo.add(HeapSizeAdvice.todo(null, 0, null, false));
        }
        String felt = "Requests stalled for up to " + Math.round(valueMs) + " ms while the JVM paused for GC.";
        String reason = "warning >= " + warning + " ms (proposal, pending KUSMA); critical >= " + criticalMs + " ms";
        return new WindowVerdict(
                state,
                valueMs,
                "ms",
                null,
                band,
                family(),
                reason,
                new Who("site", collector, null, false),
                List.of(),
                todo,
                felt,
                headline,
                List.of(),
                Set.of(),
                null);
    }

    private Double pauseTarget(WindowInput input) {
        if (input.jvm() == null || input.jvm().settings() == null) {
            return null;
        }
        try {
            var node = json.readTree(input.jvm().settings()).path("gc").path("pause_target_ms");
            if (node.isMissingNode() || node.isNull()) {
                return null;
            }
            return node.asDouble();
        } catch (Exception error) {
            return null;
        }
    }
}

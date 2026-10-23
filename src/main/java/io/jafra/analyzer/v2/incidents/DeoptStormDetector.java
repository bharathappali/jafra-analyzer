package io.jafra.analyzer.v2.incidents;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.DetectorMetrics;
import io.jafra.analyzer.v2.incidents.support.DetectorSupport;
import io.jafra.analyzer.v2.incidents.support.IncidentFlags;
import io.jafra.analyzer.v2.incidents.support.InternalInputs;
import io.jafra.analyzer.v2.incidents.support.RobustZ;
import io.jafra.analyzer.v2.incidents.support.Verdicts;
import io.jafra.analyzer.v2.store.V2Store.EventRow;

/** Deoptimization storm. One method with 50 deopts is critical. I6 blame stays here. */
@ApplicationScoped
public class DeoptStormDetector implements Detector {
    private final double warningPerS;
    private final double zMin;
    private final int criticalCount;
    private final List<String> selfFrames;

    @Inject
    public DeoptStormDetector(DetectorBands bands) {
        this(bands.deoptStorm().warningPerS(), bands.deoptStorm().z(), bands.deoptStorm().criticalCount(),
                List.of(bands.deoptStorm().selfFrames().split(",")));
    }

    public DeoptStormDetector(double warningPerS, double zMin, int criticalCount, List<String> selfFrames) {
        this.warningPerS = warningPerS;
        this.zMin = zMin;
        this.criticalCount = criticalCount;
        this.selfFrames = selfFrames;
    }

    @Override
    public String type() { return "deopt_storm"; }
    @Override
    public String group() { return "jit"; }
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
        if (in.window() == null || in.window().total(DetectorMetrics.JIT_DEOPTS).isEmpty()) {
            return WindowVerdict.unknown("no " + DetectorMetrics.JIT_DEOPTS + " row", Set.of());
        }
        double perMin = in.window().total(DetectorMetrics.JIT_DEOPTS).get().avg();
        double rate = perMin / 60d;
        Map<String, Integer> methods = new LinkedHashMap<>();
        String top = "unknown";
        String reasonName = "unknown";
        String line = "0";
        if (in.events() != null) {
            for (EventRow event : in.events().of("jit.deopt")) {
                JsonNode fields = JsonFields.read(event.fields());
                String method = fields == null ? "unknown" : fields.path("method").asText("unknown");
                int count = methods.merge(method, 1, Integer::sum);
                if (count >= methods.getOrDefault(top, 0)) {
                    top = method;
                    reasonName = fields == null ? "unknown" : fields.path("reason").asText("unknown");
                    line = fields == null ? "0" : fields.path("line").asText("0");
                }
            }
        }
        int topCount = methods.getOrDefault(top, 0);
        Baseline baseline = in.baselines() == null ? null : in.baselines().of(DetectorMetrics.JIT_DEOPTS, "", "jit", "avg");
        int n = baseline == null ? 0 : baseline.n();
        boolean critical = topCount >= criticalCount;
        boolean warning = false;
        if (!critical) {
            if (baseline == null || baseline.median() == null || n < RobustZ.WARMUP) {
                return WindowVerdict.unknown("baseline warming up (" + n + "/12)", Set.of());
            }
            double z = RobustZ.z(perMin, baseline.median(), baseline.mad(), n);
            warning = rate >= warningPerS && z >= zMin;
        }
        State state = critical ? State.CRITICAL : warning ? State.WARNING : State.OK;
        Set<String> flags = new LinkedHashSet<>();
        String todo = "check the deopt reason " + reasonName + " at " + top + ":" + line + " (e.g. a type check that keeps failing)";
        String extra = "";
        if (in.events() != null) {
            for (EventRow event : in.events().internal(InternalInputs.REDEFINE)) {
                JsonNode fields = JsonFields.read(event.fields());
                String classes = fields == null ? "0" : fields.path("classCount").asText("0");
                todo = "an agent redefined/retransformed " + classes + " classes at " + event.ts() + " (" + event.thread() + "); deopts follow instrumentation";
                String frame = event.topFrame() == null ? "" : event.topFrame();
                for (String self : selfFrames) {
                    if (!self.isBlank() && frame.contains(self)) {
                        flags.add(IncidentFlags.self_instrumentation.name());
                        extra = "; Jafra's own method timing (JDK 25)";
                    }
                }
                break;
            }
        }
        return Verdicts.of(state, rate, "per_s", null, new Band(warningPerS, (double) criticalCount), family(),
                DetectorSupport.band(HeapSizeAdvice.number(warningPerS) + "/s or " + criticalCount + " in one method") + extra,
                new Who("site", top + " · " + reasonName + " · line " + line, null, false),
                List.of(new WhereFrame(top + ":" + line, topCount, List.of("jdk.Deoptimization"))),
                List.of(todo),
                "The JIT kept throwing away compiled code (" + HeapSizeAdvice.number(rate) + "/s).",
                "Deopts " + HeapSizeAdvice.number(rate) + "/s",
                List.of("jdk.Deoptimization"), flags, false);
    }
}

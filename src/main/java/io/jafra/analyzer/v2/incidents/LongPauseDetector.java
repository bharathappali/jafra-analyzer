package io.jafra.analyzer.v2.incidents;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.JsonNode;

import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.incidents.support.InternalInputs;
import io.jafra.analyzer.v2.store.V2Store.EventRow;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

@ApplicationScoped
public class LongPauseDetector implements Detector {
    private final double warningMs;
    private final double criticalMs;
    private final double heapWarningPct;
    private final HeapSizeAdvice advice;

    @Inject
    public LongPauseDetector(
            @ConfigProperty(name = "jafra.v2.detect.long-pause.warning-ms", defaultValue = "200") double warningMs,
            @ConfigProperty(name = "jafra.v2.detect.long-pause.critical-ms", defaultValue = "1000") double criticalMs,
            @ConfigProperty(name = "jafra.v2.detect.heap-near-full.warning-pct", defaultValue = "85") double heapWarningPct,
            HeapSizeAdvice advice) {
        this.warningMs = warningMs;
        this.criticalMs = criticalMs;
        this.heapWarningPct = heapWarningPct;
        this.advice = advice;
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
        EventRow longest = longestPause(input);
        if (longest == null) {
            return WindowVerdict.ok(0d, "ms", null, new Band(warningMs, criticalMs), family(), "no gc.pause event", Set.of());
        }
        JsonNode fields = JsonFields.read(longest.fields());
        long durationUs = durationUs(longest, fields);
        double valueMs = durationUs / 1000d;
        BandChoice choice = band(input);
        Band band = new Band(choice.warningMs, criticalMs);
        List<WhereFrame> where = AllocSites.where(input);
        String phase = phase(input, gcId(fields));
        Set<String> flags = choice.flags;
        if (!where.isEmpty()) {
            flags = union(flags, "estimate");
        }
        String reason = reason(choice, phase == null, where.isEmpty(), false);
        State state = valueMs >= criticalMs ? State.CRITICAL : valueMs >= choice.warningMs ? State.WARNING : State.OK;
        if (state == State.OK) {
            return WindowVerdict.ok(valueMs, "ms", null, band, family(), reason, flags);
        }
        String collector = text(fields, "collector");
        String cause = text(fields, "cause");
        List<String> todo = new ArrayList<>();
        todo.add(collectorLine(input));
        todo.add(heapLine(input, fields));
        CauseTodo third = causeLine(input, cause, where);
        boolean usedAdvice = false;
        if (third != null) {
            todo.add(third.line());
            usedAdvice = third.usedAdvice();
        }
        if (usedAdvice) {
            reason = reason(choice, phase == null, where.isEmpty(), true);
        }
        String ms = HeapSizeAdvice.number(valueMs);
        String bandMs = HeapSizeAdvice.number(choice.warningMs);
        String headline = phase == null
                ? collector + " pause " + ms + " ms (" + cause + ") vs the " + bandMs + " ms band"
                : collector + " pause " + ms + " ms (" + cause + ", phase " + phase + ") vs the " + bandMs + " ms band";
        String whoName = phase == null ? collector + " · " + cause : collector + " · " + cause + " · " + phase;
        List<String> sources = new ArrayList<>();
        sources.add("jdk.GarbageCollection");
        sources.add("jdk.GCHeapSummary");
        if (phase != null) {
            sources.add("jdk.GCPhasePauseLevel1");
        }
        if (!where.isEmpty()) {
            sources.addAll(AllocSites.SOURCE_EVENTS);
        }
        String felt = "Requests stalled for up to " + ms + " ms while the JVM paused for GC.";
        return new WindowVerdict(
                state,
                valueMs,
                "ms",
                null,
                band,
                family(),
                reason,
                new Who("site", whoName, null, false),
                where,
                todo,
                felt,
                headline,
                sources,
                flags,
                null);
    }

    /**
     * Warning is the max of the floor, an explicit {@code -XX:MaxGCPauseMillis}, and 3 × p99.
     * {@code GCConfiguration.pauseTarget} is stored as {@code gc.pause_target_ms}. Collectors report
     * their default there when the flag is unset. What Parallel and Serial report is UNVERIFIED.
     * That field is not a band term.
     */
    private BandChoice band(WindowInput input) {
        double warning = warningMs;
        String source = "band " + HeapSizeAdvice.number(warningMs) + " ms (floor, proposal, pending KUSMA)";
        Double flag = pauseFlag(input);
        if (flag != null && flag >= warning) {
            warning = flag;
            source = "band " + HeapSizeAdvice.number(flag) + " ms (-XX:MaxGCPauseMillis=" + HeapSizeAdvice.number(flag)
                    + " in jvmArguments)";
        }
        Baseline baseline = input.baselines().of("gc.pause.us", "", group(), "max");
        boolean proven = baseline.n() >= 60 && baseline.events() >= 50;
        Set<String> flags = proven ? Set.of() : Set.of("unproven_baseline");
        if (proven && baseline.p99() != null) {
            double p99Ms = baseline.p99() / 1000d;
            double term = 3d * p99Ms;
            if (term >= warning) {
                warning = term;
                source = "band " + HeapSizeAdvice.number(term) + " ms (3 × baseline p99 " + HeapSizeAdvice.number(p99Ms)
                        + " ms over " + baseline.n() + " windows, " + baseline.events() + " GCs)";
            }
        }
        return new BandChoice(warning, source, flags);
    }

    private String reason(BandChoice choice, boolean missingPhase, boolean missingAlloc, boolean usedAdvice) {
        String reason = choice.source;
        if (missingPhase) {
            reason += "; no GCPhasePauseLevel1 for this GC";
        }
        if (missingAlloc) {
            reason += "; no allocation samples";
        }
        if (usedAdvice) {
            reason += "; flags read from jvmArguments; JAVA_TOOL_OPTIONS unverified";
        }
        return reason;
    }

    private static EventRow longestPause(WindowInput input) {
        EventRow longest = null;
        long best = Long.MIN_VALUE;
        for (EventRow event : input.events().of("gc.pause")) {
            Long duration = durationOrNull(event, JsonFields.read(event.fields()));
            if (duration == null) {
                continue;
            }
            if (longest == null || duration > best) {
                longest = event;
                best = duration;
            }
        }
        return longest;
    }

    private static long durationUs(EventRow event, JsonNode fields) {
        Long duration = durationOrNull(event, fields);
        return duration == null ? 0L : duration;
    }

    private static Long durationOrNull(EventRow event, JsonNode fields) {
        if (event.durationUs() != null) {
            return event.durationUs();
        }
        if (fields != null && fields.path("duration_us").isNumber()) {
            return fields.path("duration_us").asLong();
        }
        return null;
    }

    private static Long gcId(JsonNode fields) {
        if (fields == null || !fields.path("gc_id").isNumber()) {
            return null;
        }
        return fields.path("gc_id").asLong();
    }

    private static String phase(WindowInput input, Long gcId) {
        if (gcId == null) {
            return null;
        }
        EventRow best = null;
        long bestDuration = Long.MIN_VALUE;
        for (EventRow event : input.events().internal(InternalInputs.GC_PHASE_L1)) {
            JsonNode fields = JsonFields.read(event.fields());
            if (fields == null || !fields.path("gc_id").isNumber() || fields.path("gc_id").asLong() != gcId) {
                continue;
            }
            long duration = event.durationUs() == null ? Long.MIN_VALUE : event.durationUs();
            if (best == null || duration > bestDuration) {
                best = event;
                bestDuration = duration;
            }
        }
        if (best == null) {
            return null;
        }
        String name = text(JsonFields.read(best.fields()), "phase");
        return "unknown".equals(name) ? null : name;
    }

    private String collectorLine(WindowInput input) {
        String young = named(input.settings().youngCollector());
        String old = named(input.settings().oldCollector());
        Optional<String> target = input.settings().argument("-XX:MaxGCPauseMillis");
        if (target.isPresent()) {
            return "collector " + young + "/" + old + "; pause target " + target.get() + " ms (-XX:MaxGCPauseMillis)";
        }
        return "collector " + young + "/" + old + "; no -XX:MaxGCPauseMillis in jvmArguments";
    }

    private static String heapLine(WindowInput input, JsonNode fields) {
        boolean xmx = input.settings().argument("-Xmx").isPresent()
                || input.settings().argument("-XX:MaxHeapSize").isPresent();
        String max = HeapSizeAdvice.bytes(heapMax(input));
        String before = HeapSizeAdvice.bytes(number(fields, "heap_before"));
        String after = HeapSizeAdvice.bytes(number(fields, "heap_after"));
        if (xmx) {
            return "-Xmx is " + max + "; heap was " + before + " -> " + after + " around the pause";
        }
        return "heap max is " + max + " (no -Xmx); heap was " + before + " -> " + after + " around the pause";
    }

    private CauseTodo causeLine(WindowInput input, String cause, List<WhereFrame> where) {
        if ("System.gc()".equals(cause)) {
            String top = systemGcFrame(input);
            if (top == null) {
                return new CauseTodo("remove the System.gc() call or add -XX:+DisableExplicitGC", false);
            }
            return new CauseTodo("remove the System.gc() call at " + top + " or add -XX:+DisableExplicitGC", false);
        }
        if ("G1 Humongous Allocation".equals(cause)) {
            return new CauseTodo("raise -XX:G1HeapRegionSize", false);
        }
        if ("Allocation Failure".equals(cause) || "G1 Evacuation Pause".equals(cause)) {
            Double occupancy = occupancy(input);
            if (occupancy != null && occupancy >= heapWarningPct) {
                return new CauseTodo(advice.todo(input.settings(), input.window()), true);
            }
            if (where.isEmpty()) {
                return new CauseTodo("reduce allocation (no allocation samples)", false);
            }
            WhereFrame top = where.get(0);
            return new CauseTodo(
                    "reduce allocation at " + top.frame() + " (" + HeapSizeAdvice.number(top.sharePct())
                            + "% of sampled bytes)",
                    false);
        }
        return null;
    }

    private static String systemGcFrame(WindowInput input) {
        for (EventRow event : input.events().of("gc.system_gc")) {
            if (event.topFrame() != null && !event.topFrame().isBlank()) {
                return event.topFrame();
            }
        }
        return null;
    }

    private static Double occupancy(WindowInput input) {
        Optional<RollupRow> row = input.window().total("heap.occupancy.pct");
        if (row.isPresent() && row.get().count() > 0) {
            return row.get().min();
        }
        return input.window().lastBefore("heap.occupancy.pct").filter(prior -> prior.count() > 0).map(RollupRow::min).orElse(null);
    }

    private static Double heapMax(WindowInput input) {
        Optional<RollupRow> row = input.window().total("heap.max.bytes");
        if (row.isPresent() && row.get().count() > 0 && row.get().avg() > 0d) {
            return row.get().avg();
        }
        return input.settings().heapMaxBytes();
    }

    private static Double pauseFlag(WindowInput input) {
        Optional<String> raw = input.settings().argument("-XX:MaxGCPauseMillis");
        if (raw.isEmpty()) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw.get().trim());
            if (!Double.isFinite(value) || value <= 0d) {
                return null;
            }
            return value;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static String text(JsonNode fields, String name) {
        if (fields == null) {
            return "unknown";
        }
        String value = fields.path(name).asText("");
        return value.isBlank() ? "unknown" : value;
    }

    private static Double number(JsonNode fields, String name) {
        if (fields == null || !fields.path(name).isNumber()) {
            return null;
        }
        return fields.path(name).asDouble();
    }

    private static String named(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static Set<String> union(Set<String> flags, String extra) {
        LinkedHashSet<String> combined = new LinkedHashSet<>(flags);
        combined.add(extra);
        return combined;
    }

    private record BandChoice(double warningMs, String source, Set<String> flags) {}

    private record CauseTodo(String line, boolean usedAdvice) {}
}

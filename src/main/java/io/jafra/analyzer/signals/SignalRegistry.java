package io.jafra.analyzer.signals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads `signals.yaml` and explains why a signal is missing. */
public final class SignalRegistry {
    private static final SignalRegistry INSTANCE = load();
    private static final ThreadLocal<Facts> CURRENT = new ThreadLocal<>();

    private final List<Signal> signals;
    private final Map<String, String> metricToSignal;

    private SignalRegistry(List<Signal> signals) {
        this.signals = List.copyOf(signals);
        Map<String, String> metrics = new LinkedHashMap<>();
        List<Signal> prefixes = new ArrayList<>();
        for (Signal signal : signals) {
            for (String metric : signal.metrics) {
                metrics.put(metric, signal.id);
            }
            if (signal.metricPrefix != null) {
                prefixes.add(signal);
            }
        }
        this.metricToSignal = Map.copyOf(metrics);
        this.prefixes = List.copyOf(prefixes);
    }

    private final List<Signal> prefixes;

    public static SignalRegistry get() {
        return INSTANCE;
    }

    public static void bind(Facts facts) {
        CURRENT.set(facts == null ? Facts.empty() : facts);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Facts current() {
        Facts facts = CURRENT.get();
        return facts == null ? Facts.empty() : facts;
    }

    public List<Signal> signals() {
        return signals;
    }

    public String metricSignal(String metricId) {
        String exact = metricToSignal.get(metricId);
        if (exact != null) {
            return exact;
        }
        String prefixMatch = null;
        int length = -1;
        for (Signal signal : prefixes) {
            if (metricId != null && metricId.startsWith(signal.metricPrefix) && signal.metricPrefix.length() > length) {
                prefixMatch = signal.id;
                length = signal.metricPrefix.length();
            }
        }
        return prefixMatch;
    }

    public Verdict evaluate(Signal signal, Facts facts) {
        if (signal.alwaysReason != null) {
            return unavailable(signal, signal.alwaysReason, null, alwaysHint(signal));
        }
        if (signal.minJdk != null && facts.jdkFeature != null && facts.jdkFeature < signal.minJdk) {
            return unavailable(signal, "jdk_version", signal.minJdk, jdkHint(signal));
        }
        if (!signal.gcs.isEmpty() && facts.gc != null && signal.gcs.stream().noneMatch(facts.gc::equals)) {
            return unavailable(signal, "gc_type", null, gcHint(signal, facts));
        }
        if (!signal.requiresFlags.isEmpty() && facts.sawFlags) {
            for (String flag : signal.requiresFlags) {
                String value = facts.flags.get(flag);
                if (value == null || "off".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                    return unavailable(signal, "jvm_flag", null, flagHint(flag));
                }
            }
        }
        if (signal.requiresContainer && !facts.inContainer) {
            return unavailable(signal, "not_in_container", null, "This signal needs a container (jdk.ContainerConfiguration).");
        }
        if (signal.replacedByProfiler && facts.profiler && !overlaps(signal.events, facts.seen)) {
            return unavailable(signal, "disabled_by_profiler", null, "Provided by async-profiler; see top?kind=alloc.");
        }
        if (signal.thresholdMs != null && signal.thresholdEvent != null) {
            Double configured = facts.thresholdMs.get(signal.thresholdEvent);
            if (configured != null && configured >= signal.thresholdMs && !overlaps(signal.events, facts.seen)) {
                return unavailable(signal, "preset_threshold", null,
                        "Events below " + signal.thresholdMs.intValue() + " ms aren't recorded with preset low; use jafra.io/profile=medium.");
            }
        }
        return new Verdict(signal.id, "available", null, null, null);
    }

    public List<Verdict> evaluateAll(Facts facts) {
        List<Verdict> verdicts = new ArrayList<>();
        for (Signal signal : signals) {
            verdicts.add(evaluate(signal, facts));
        }
        return verdicts;
    }

    /** Why an absent v1 subsystem has none of its expected events. Null when no signal owns them. */
    public static Verdict explain(List<String> expected, Facts facts) {
        Signal best = null;
        int overlap = 0;
        for (Signal signal : INSTANCE.signals) {
            int count = 0;
            for (String event : signal.events) {
                if (expected.contains(event)) {
                    count++;
                }
            }
            if (count > overlap) {
                overlap = count;
                best = signal;
            }
        }
        if (best == null || overlap == 0) {
            return null;
        }
        Verdict verdict = INSTANCE.evaluate(best, facts);
        if ("available".equals(verdict.status())) {
            return new Verdict(best.id, "unavailable", "no_data_in_window", null, "No " + best.id + " events in this window.");
        }
        return verdict;
    }

    private static Verdict unavailable(Signal signal, String reason, Integer minJdk, String hint) {
        return new Verdict(signal.id, "unavailable", reason, minJdk, hint);
    }

    private static String jdkHint(Signal signal) {
        return "Upgrade to JDK " + signal.minJdk + "+ to see this (" + String.join(", ", signal.events) + ").";
    }

    private static String gcHint(Signal signal, Facts facts) {
        String actual = facts.gc == null ? "an unknown collector" : facts.gc;
        String tail = "ergonomic".equals(facts.gcSelectedBy)
                ? " (ergonomic: effectiveCpuCount=" + (facts.effectiveCpus == null ? "?" : facts.effectiveCpus)
                        + " < 2 or memory limit < 1792 MB)."
                : ".";
        return "Only with " + String.join(" or ", signal.gcs) + "; this JVM runs " + actual + tail;
    }

    private static String flagHint(String flag) {
        if ("NativeMemoryTracking".equals(flag)) {
            return "Set jafra.io/nmt=summary.";
        }
        return "Enable " + flag + ".";
    }

    private static String alwaysHint(Signal signal) {
        if ("not_exposed_by_cgroup".equals(signal.alwaysReason)) {
            return "memory_request is not exposed by the cgroup.";
        }
        return signal.alwaysReason;
    }

    private static boolean overlaps(List<String> events, Set<String> seen) {
        for (String event : events) {
            if (seen.contains(event)) {
                return true;
            }
        }
        return false;
    }

    private static SignalRegistry load() {
        try (InputStream in = SignalRegistry.class.getResourceAsStream("/signals.yaml")) {
            if (in == null) {
                throw new IllegalStateException("signals.yaml is missing");
            }
            return new SignalRegistry(parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException error) {
            throw new IllegalStateException("cannot read signals.yaml", error);
        }
    }

    static List<Signal> parse(String yaml) {
        List<Signal> signals = new ArrayList<>();
        Builder current = null;
        for (String raw : yaml.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("- ")) {
                if (current != null) {
                    signals.add(current.build());
                }
                current = new Builder();
                line = line.substring(2).strip();
            }
            if (current == null) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            current.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
        }
        if (current != null) {
            signals.add(current.build());
        }
        return signals;
    }

    private static List<String> list(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String text = value;
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1);
        }
        if (text.isBlank()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return List.copyOf(items);
    }

    public record Signal(
            String id,
            List<String> events,
            Integer minJdk,
            List<String> gcs,
            List<String> requiresFlags,
            boolean requiresContainer,
            boolean replacedByProfiler,
            String alwaysReason,
            String thresholdEvent,
            Double thresholdMs,
            List<String> metrics,
            String metricPrefix) {}

    public record Verdict(String id, String status, String reason, Integer minJdk, String hint) {}

    public record Facts(
            Integer jdkFeature,
            String gc,
            String gcSelectedBy,
            Map<String, String> flags,
            boolean sawFlags,
            boolean inContainer,
            Set<String> seen,
            boolean profiler,
            Map<String, Double> thresholdMs,
            Long effectiveCpus,
            Long memLimit) {

        public static Facts empty() {
            return new Facts(null, null, null, Map.of(), false, false, Set.of(), false, Map.of(), null, null);
        }
    }

    private static final class Builder {
        private String id;
        private List<String> events = List.of();
        private Integer minJdk;
        private List<String> gcs = List.of();
        private List<String> requiresFlags = List.of();
        private boolean requiresContainer;
        private boolean replacedByProfiler;
        private String alwaysReason;
        private String thresholdEvent;
        private Double thresholdMs;
        private List<String> metrics = List.of();
        private String metricPrefix;

        void put(String key, String value) {
            switch (key) {
                case "id" -> id = value;
                case "events" -> events = list(value);
                case "min_jdk" -> minJdk = Integer.valueOf(value);
                case "gcs" -> gcs = list(value);
                case "requires_flags" -> requiresFlags = list(value);
                case "requires_container" -> requiresContainer = Boolean.parseBoolean(value);
                case "replaced_by_profiler" -> replacedByProfiler = Boolean.parseBoolean(value);
                case "always_reason" -> alwaysReason = value;
                case "threshold_event" -> thresholdEvent = value;
                case "threshold_ms" -> thresholdMs = Double.valueOf(value);
                case "metrics" -> metrics = list(value);
                case "metric_prefix" -> metricPrefix = value;
                default -> {
                }
            }
        }

        Signal build() {
            return new Signal(id, events, minJdk, gcs, requiresFlags, requiresContainer, replacedByProfiler, alwaysReason,
                    thresholdEvent, thresholdMs, metrics, metricPrefix);
        }
    }
}

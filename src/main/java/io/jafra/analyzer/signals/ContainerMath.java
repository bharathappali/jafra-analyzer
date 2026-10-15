package io.jafra.analyzer.signals;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Container and GC facts derived from JFR, without the Kubernetes API. */
public final class ContainerMath {
    public static final long SERIAL_MEM_BYTES = 1792L * 1024L * 1024L;
    private static final Pattern USE_GC = Pattern.compile("Use(G1|Z|Parallel|Serial|Shenandoah)GC");

    private ContainerMath() {}

    public static Integer jdkFeature(String version) {
        if (version == null) {
            return null;
        }
        java.util.regex.Matcher matcher = Pattern.compile("(\\d+)").matcher(version);
        if (!matcher.find()) {
            return null;
        }
        return Integer.parseInt(matcher.group(1));
    }

    public static Double cpuLimitCores(Long quota, Long period) {
        if (quota == null || period == null || quota <= 0L || period <= 0L) {
            return null;
        }
        return quota.doubleValue() / period.doubleValue();
    }

    /** Nearest 10 millicores. Shares of 2 are BestEffort and often round to 0. */
    public static Long cpuRequestMillicores(Long shares) {
        if (shares == null || shares < 0L) {
            return null;
        }
        double raw = shares * 1000.0 / 1024.0;
        return Math.round(raw / 10.0) * 10L;
    }

    public static String requestQuality(String containerType) {
        if (containerType != null && containerType.toLowerCase(Locale.ROOT).contains("cgroupv2")) {
            return "approx_v2_weight_mapping";
        }
        return "approx";
    }

    public static String cpuQos(Long shares) {
        return shares != null && shares == 2L ? "best_effort" : null;
    }

    public static Long memLimit(Long raw) {
        if (raw == null || raw < 0L) {
            return null;
        }
        return raw;
    }

    public static String gcFamily(String young, String old, Map<String, String> flags) {
        String blob = ((young == null ? "" : young) + " " + (old == null ? "" : old)).toLowerCase(Locale.ROOT);
        if (blob.contains("shenandoah")) {
            return "Shenandoah";
        }
        if (blob.contains("g1")) {
            return "G1";
        }
        if (blob.contains("zgc")) {
            return "Z";
        }
        if (blob.contains("parallel")) {
            return "Parallel";
        }
        if (blob.contains("serial")) {
            return "Serial";
        }
        if (truthy(flags.get("UseShenandoahGC"))) {
            return "Shenandoah";
        }
        if (truthy(flags.get("UseG1GC"))) {
            return "G1";
        }
        if (truthy(flags.get("UseZGC"))) {
            return "Z";
        }
        if (truthy(flags.get("UseParallelGC"))) {
            return "Parallel";
        }
        if (truthy(flags.get("UseSerialGC"))) {
            return "Serial";
        }
        return null;
    }

    /** Ergonomic when flags were seen and no enabled Use*GC flag came from the command line. */
    public static String gcSelectedBy(boolean sawFlags, List<Flag> flags) {
        if (!sawFlags) {
            return null;
        }
        for (Flag flag : flags) {
            if (flag.name() == null || !USE_GC.matcher(flag.name()).matches() || !truthy(flag.value())) {
                continue;
            }
            if (flag.origin() != null && flag.origin().toLowerCase(Locale.ROOT).contains("command line")) {
                return "command_line";
            }
        }
        return "ergonomic";
    }

    public static boolean serialInUse(String gc, Map<String, String> flags) {
        return "Serial".equals(gc) || truthy(flags.get("UseSerialGC"));
    }

    public static boolean ergonomicSerial(String selectedBy, boolean serial, Long cpus, Long memLimit) {
        if (!"ergonomic".equals(selectedBy) || !serial) {
            return false;
        }
        boolean smallCpu = cpus != null && cpus < 2L;
        boolean smallMem = memLimit != null && memLimit < SERIAL_MEM_BYTES;
        return smallCpu || smallMem;
    }

    public static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text) || "yes".equals(text);
    }

    public static Double millis(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        try {
            if (text.endsWith("ms")) {
                return Double.parseDouble(text.substring(0, text.length() - 2).trim());
            }
            if (text.endsWith("us")) {
                return Double.parseDouble(text.substring(0, text.length() - 2).trim()) / 1_000.0;
            }
            if (text.endsWith("ns")) {
                return Double.parseDouble(text.substring(0, text.length() - 2).trim()) / 1_000_000.0;
            }
            if (text.endsWith("s")) {
                return Double.parseDouble(text.substring(0, text.length() - 1).trim()) * 1_000.0;
            }
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public record Flag(String name, String value, String origin) {}
}

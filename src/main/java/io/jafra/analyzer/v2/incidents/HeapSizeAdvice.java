package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.store.V2Store.RollupRow;

/**
 * Shared heap-size todo. Flag values come only from {@code jvmArguments}.
 * Detectors that use this append "flags read from jvmArguments; JAVA_TOOL_OPTIONS unverified".
 */
@ApplicationScoped
public class HeapSizeAdvice {
    private final double containerWarningPct;

    @Inject
    public HeapSizeAdvice(DetectorBands bands) {
        this(bands.containerNearLimit().warningPct());
    }

    public HeapSizeAdvice(double containerWarningPct) {
        this.containerWarningPct = containerWarningPct;
    }

    public String todo(JvmSettings settings, RollupView window) {
        JvmSettings resolved = settings == null ? new JvmSettings(null) : settings;
        Double heapMax = positive(window, "heap.max.bytes", false);
        if (heapMax == null) {
            heapMax = resolved.heapMaxBytes();
        }
        Double podLimit = positive(window, "container.limit.bytes", false);
        if (podLimit == null) {
            podLimit = resolved.podMemoryLimitBytes();
        }
        Double usage = positive(window, "container.usage.pct_limit", true);
        if (usage != null && usage >= containerWarningPct) {
            return "raise the pod memory limit " + bytes(podLimit)
                    + " first: metaspace, threads and direct buffers need room outside the heap";
        }
        boolean xmx = resolved.argument("-Xmx").isPresent() || resolved.argument("-XX:MaxHeapSize").isPresent();
        if (!xmx) {
            Optional<String> percent = resolved.argument("-XX:MaxRAMPercentage");
            String from = percent.isEmpty() ? "25% (JDK default)" : number(parse(percent.get())) + "%";
            String limit = podLimit == null
                    ? "no pod memory limit set"
                    : "pod memory limit " + bytes(podLimit);
            return "raise -XX:MaxRAMPercentage from " + from + " (" + limit + ", heap max now " + bytes(heapMax) + ")";
        }
        return "raise -Xmx from " + bytes(heapMax);
    }

    /**
     * Lower variant of {@link #todo}: same flag parsing, used by overcommitted_config.
     * Target heap is 80% of the pod limit ({@code floor(80)}).
     */
    public List<String> lower(JvmSettings settings, RollupView window) {
        JvmSettings resolved = settings == null ? new JvmSettings(null) : settings;
        Double heapMax = positive(window, "heap.max.bytes", false);
        if (heapMax == null) {
            heapMax = resolved.heapMaxBytes();
        }
        Double podLimit = positive(window, "container.limit.bytes", false);
        if (podLimit == null) {
            podLimit = resolved.podMemoryLimitBytes();
        }
        boolean xmx = resolved.argument("-Xmx").isPresent() || resolved.argument("-XX:MaxHeapSize").isPresent();
        String heapLine;
        if (!xmx) {
            Optional<String> percent = resolved.argument("-XX:MaxRAMPercentage");
            String from = percent.isEmpty() ? "25" : number(parse(percent.get()));
            heapLine = "lower -XX:MaxRAMPercentage from " + from + "% to ≤ 80% (heap ≤ " + bytes(scale(podLimit, 0.8)) + ")";
        } else {
            heapLine = "lower -Xmx from " + bytes(heapMax) + " to ≤ " + bytes(scale(podLimit, 0.8));
        }
        return List.of(
                heapLine,
                "or raise the pod memory limit from " + bytes(podLimit) + " to ≥ " + bytes(scale(heapMax, 1.25)));
    }

    private static Double scale(Double value, double factor) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        return value * factor;
    }

    /** GiB or MiB with one decimal. Smaller values use KiB, then bytes. */
    public static String bytes(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0d) {
            return "unknown";
        }
        if (value >= 1_073_741_824d) {
            return String.format(Locale.ROOT, "%.1f GiB", value / 1_073_741_824d);
        }
        if (value >= 1_048_576d) {
            return String.format(Locale.ROOT, "%.1f MiB", value / 1_048_576d);
        }
        if (value >= 1024d) {
            return String.format(Locale.ROOT, "%.1f KiB", value / 1024d);
        }
        return String.format(Locale.ROOT, "%.1f B", value);
    }

    static String number(double value) {
        if (!Double.isFinite(value)) {
            return "unknown";
        }
        if (Math.abs(value - Math.rint(value)) < 1e-6) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static Double positive(RollupView window, String metric, boolean max) {
        if (window == null) {
            return null;
        }
        Optional<RollupRow> row = window.total(metric);
        if (row.isEmpty() || row.get().count() <= 0) {
            return null;
        }
        double value = max ? row.get().max() : row.get().avg();
        if (!Double.isFinite(value) || value <= 0d) {
            return null;
        }
        return value;
    }

    private static double parse(String raw) {
        if (raw == null) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException error) {
            return Double.NaN;
        }
    }
}

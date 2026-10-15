package io.jafra.analyzer.ingest.decode;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Ingest-time marker rules. A pause or ratio under the threshold emits nothing. */
public final class MarkerRules {
    private MarkerRules() {}

    public static Marker gcPause(long tNs, double durMs, Long gcId) {
        if (durMs < 20.0) {
            return null;
        }
        String severity = durMs >= 500.0 ? "critical" : durMs >= 100.0 ? "warn" : "info";
        String ref = gcId == null ? null : "{\"gc_id\":" + gcId + "}";
        return new Marker(tNs, tNs + millisToNs(durMs), "gc_pause", severity, durMs, "ms", ref);
    }

    public static Marker fullGc(long tNs, Double pauseMs, Long gcId) {
        String ref = gcId == null ? null : "{\"gc_id\":" + gcId + "}";
        return new Marker(tNs, null, "full_gc", "critical", pauseMs, "ms", ref);
    }

    public static Marker named(long tNs, String kind, String severity, Double magnitude, String unit) {
        return new Marker(tNs, null, kind, severity, magnitude, unit, null);
    }

    public static List<Marker> safepoint(long tNs, double totalMs, Double ttspMs) {
        List<Marker> markers = new ArrayList<>();
        if (totalMs >= 10.0) {
            markers.add(new Marker(tNs, tNs + millisToNs(totalMs), "safepoint", totalMs >= 50.0 ? "warn" : "info", totalMs, "ms", null));
        }
        if (ttspMs != null && ttspMs >= 5.0) {
            markers.add(new Marker(tNs, null, "safepoint", ttspMs >= 50.0 ? "warn" : "info", ttspMs, "ms", "{\"part\":\"ttsp\"}"));
        }
        return markers;
    }

    public static Marker vmOp(long tNs, double durMs) {
        if (durMs < 50.0) {
            return null;
        }
        return new Marker(tNs, tNs + millisToNs(durMs), "vm_op", "warn", durMs, "ms", null);
    }

    public static Marker cpuThrottle(long tNs, double ratio) {
        if (ratio < 0.2) {
            return null;
        }
        return new Marker(tNs, null, "cpu_throttle", ratio >= 0.5 ? "critical" : "warn", ratio, "ratio", null);
    }

    public static Marker exceptionBurst(long tNs, double perSecond) {
        if (perSecond < 10_000.0) {
            return null;
        }
        return new Marker(tNs, null, "exception_burst", "warn", perSecond, "per_s", null);
    }

    public static List<Marker> deoptBurst(List<Long> timesNs) {
        TreeMap<Long, Integer> counts = new TreeMap<>();
        for (Long time : timesNs) {
            if (time == null) {
                continue;
            }
            long bucket = Math.floorDiv(time, 10_000_000_000L);
            counts.merge(bucket, 1, Integer::sum);
        }
        List<Marker> markers = new ArrayList<>();
        for (var entry : counts.entrySet()) {
            if (entry.getValue() >= 50) {
                long t = entry.getKey() * 10_000_000_000L;
                markers.add(new Marker(t, t + 10_000_000_000L, "deopt_burst", "warn", entry.getValue().doubleValue(), "count", null));
            }
        }
        return markers;
    }

    private static long millisToNs(double millis) {
        return (long) (millis * 1_000_000.0);
    }

    public record Marker(long t, Long tEnd, String kind, String severity, Double magnitude, String unit, String ref) {}
}

package io.jafra.analyzer.findings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic finding rules. An empty input does not fire. HEALTHY is the only result when nothing else fires. */
public final class FindingDetectors {
    private FindingDetectors() {}

    public static List<Map<String, Object>> evaluate(WindowSnapshot window) {
        List<Map<String, Object>> findings = new ArrayList<>();
        add(findings, gcPressure(window));
        add(findings, allocationStorm(window));
        add(findings, memoryLeak(window));
        add(findings, prematurePromotion(window));
        add(findings, humongous(window));
        add(findings, evacuation(window));
        add(findings, metaspace(window));
        add(findings, throttling(window));
        add(findings, hotspot(window));
        add(findings, locks(window));
        add(findings, pool(window));
        add(findings, ioLatency(window));
        add(findings, safepoint(window));
        add(findings, deopt(window));
        add(findings, codeCache(window));
        add(findings, nativeLeak(window));
        add(findings, threadLeak(window));
        add(findings, exceptions(window));
        add(findings, containerOom(window));
        add(findings, ergonomic(window));
        if (findings.isEmpty() && window.observed) {
            findings.add(finding("HEALTHY", "info", Map.of()));
        }
        return findings;
    }

    private static Map<String, Object> gcPressure(WindowSnapshot window) {
        double wallMs = window.windowNs / 1_000_000.0;
        if (wallMs <= 0.0) {
            return null;
        }
        double overhead = window.pauseMs / wallMs * 100.0;
        double youngPerSec = window.youngGcs / (window.windowNs / 1_000_000_000.0);
        boolean z = "Z".equals(window.collector);
        boolean third = z ? window.allocationStalls > 0 : heapPct(window) > 70.0;
        if (overhead > 10.0 && youngPerSec > 1.0 && third) {
            return finding("GC_PRESSURE", "warn", Map.of(
                    "gc.overhead_pct", overhead, "young_gc_per_s", youngPerSec, "allocation_stalls", window.allocationStalls));
        }
        return null;
    }

    private static Map<String, Object> allocationStorm(WindowSnapshot window) {
        if (window.allocPerSec.size() < 3 || window.heapAfter.size() < 3) {
            return null;
        }
        double median = median(window.allocPerSec);
        double peak = window.allocPerSec.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        Fit floor = fit(window.heapAfter);
        boolean flat = floor.slope() <= 0.0 || Math.abs(floor.slope() * (window.heapAfter.size() - 1)) < 0.05 * Math.max(1.0, window.heapAfter.get(0));
        if (median > 0.0 && peak >= 3.0 * median && flat) {
            return finding("ALLOCATION_STORM", "warn", Map.of("alloc.peak_per_s", peak, "alloc.median_per_s", median));
        }
        return null;
    }

    private static Map<String, Object> memoryLeak(WindowSnapshot window) {
        if (window.heapAfter.size() < 10 || window.heapMax == null || window.heapMax <= 0) {
            return null;
        }
        Fit floor = fit(window.heapAfter);
        double last = window.heapAfter.get(window.heapAfter.size() - 1);
        double pct = last / window.heapMax * 100.0;
        if (floor.slope() > 0.0 && floor.r2() > 0.7 && pct > 50.0 && last > window.heapAfter.get(0)) {
            return finding("MEMORY_LEAK", "critical", Map.of("r2", floor.r2(), "heap.after_pct", pct));
        }
        return null;
    }

    private static Map<String, Object> prematurePromotion(WindowSnapshot window) {
        boolean storm = allocationStorm(window) != null;
        boolean highPromotion = window.allocBytes > 0.0 && window.promotedBytes / window.allocBytes > 0.1;
        if (window.lowTenuringGcs >= 5 && window.survivorFullGcs >= 5 && highPromotion && !storm) {
            return finding("PREMATURE_PROMOTION", "warn", Map.of(
                    "low_tenuring_gcs", window.lowTenuringGcs, "promoted_bytes", window.promotedBytes));
        }
        return null;
    }

    private static Map<String, Object> humongous(WindowSnapshot window) {
        if (!"G1".equals(window.collector)) {
            return null;
        }
        double pct = window.regionCount <= 0 ? 0.0 : window.humongousRegions * 100.0 / window.regionCount;
        if (pct > 10.0 || window.humongousCause) {
            return finding("HUMONGOUS_ALLOC", "warn", Map.of("humongous_region_pct", pct));
        }
        return null;
    }

    private static Map<String, Object> evacuation(WindowSnapshot window) {
        if (window.evacuationFailures > 0) {
            return finding("EVACUATION_FAILURE", "critical", Map.of("count", window.evacuationFailures));
        }
        return null;
    }

    private static Map<String, Object> metaspace(WindowSnapshot window) {
        if (window.metaspace.size() < 3) {
            return null;
        }
        Fit used = fit(window.metaspace);
        boolean rising = window.classesLoaded > window.classesUnloaded && window.classesLoaded > 0.0;
        if (used.slope() > 0.0 && rising && window.metadataGcCause) {
            return finding("METASPACE_GROWTH", "warn", Map.of("classes.loaded", window.classesLoaded));
        }
        return null;
    }

    private static Map<String, Object> throttling(WindowSnapshot window) {
        if (window.elapsedSlices <= 0.0 || window.cpuLimitCores == null || window.cpuLimitCores <= 0.0) {
            return null;
        }
        double ratio = window.throttledSlices / window.elapsedSlices;
        double limit = window.cpuLimitCores;
        if (ratio > 0.2 && Math.abs(window.jvmCores - limit) <= 0.1 * limit) {
            return finding("CPU_THROTTLING", "warn", Map.of("throttle_ratio", ratio, "jvm_cores", window.jvmCores));
        }
        return null;
    }

    private static Map<String, Object> hotspot(WindowSnapshot window) {
        if (window.effectiveCpus == null || window.effectiveCpus <= 0.0) {
            return null;
        }
        if (window.jvmCores > 0.8 * window.effectiveCpus && window.topMethodShare > 0.2 && window.topSamples >= 300) {
            return finding("CPU_HOTSPOT", "warn", Map.of("top.share", window.topMethodShare, "samples", window.topSamples));
        }
        return null;
    }

    private static Map<String, Object> locks(WindowSnapshot window) {
        double wallMs = window.windowNs / 1_000_000.0;
        if (wallMs <= 0.0) {
            return null;
        }
        double blocked = window.lockMs / wallMs * 100.0;
        if (blocked > 10.0 && window.monitorShare > 0.5) {
            return finding("LOCK_CONTENTION", "warn", Map.of("blocked_pct", blocked, "monitor.share", window.monitorShare));
        }
        return null;
    }

    private static Map<String, Object> pool(WindowSnapshot window) {
        if (window.parkedGroupShare > 0.8) {
            return finding("POOL_EXHAUSTION_OR_DB_WAIT", "warn", Map.of("parked_share", window.parkedGroupShare));
        }
        return null;
    }

    private static Map<String, Object> ioLatency(WindowSnapshot window) {
        Double socket = spike(window.socketP99);
        Double file = spike(window.fileP99);
        if (socket != null) {
            return finding("IO_LATENCY", "warn", Map.of("io.socket_read_ms.p99", socket));
        }
        if (file != null) {
            return finding("IO_LATENCY", "warn", Map.of("io.file_read_ms.p99", file));
        }
        return null;
    }

    private static Map<String, Object> safepoint(WindowSnapshot window) {
        double seconds = window.windowNs / 1_000_000_000.0;
        double wallMs = window.windowNs / 1_000_000.0;
        if (seconds <= 0.0) {
            return null;
        }
        double perSec = window.safepointCount / seconds;
        double pct = wallMs <= 0.0 ? 0.0 : window.safepointMs / wallMs * 100.0;
        if (perSec > 10.0 || pct > 5.0) {
            return finding("SAFEPOINT_STORM", "warn", Map.of("safepoint.per_s", perSec, "safepoint.time_pct", pct));
        }
        return null;
    }

    private static Map<String, Object> deopt(WindowSnapshot window) {
        double minutes = window.windowNs / 60_000_000_000.0;
        if (minutes <= 5.0) {
            return null;
        }
        double perMin = window.deoptCount / minutes;
        if (perMin > 100.0) {
            return finding("JIT_DEOPT_STORM", "warn", Map.of("deopt.per_min", perMin));
        }
        return null;
    }

    private static Map<String, Object> codeCache(WindowSnapshot window) {
        if (window.codeCacheFull) {
            return finding("CODE_CACHE_FULL", "critical", Map.of());
        }
        return null;
    }

    private static Map<String, Object> nativeLeak(WindowSnapshot window) {
        if (window.nmtRising) {
            return finding("NATIVE_LEAK", "warn", Map.of("nmt", "rising"));
        }
        if (window.rss.size() < 3 || window.heapCommitted.size() < 3) {
            return null;
        }
        Fit rss = fit(window.rss);
        Fit committed = fit(window.heapCommitted);
        boolean flat = Math.abs(committed.slope() * (window.heapCommitted.size() - 1)) < 0.05 * Math.max(1.0, window.heapCommitted.get(0));
        if (rss.slope() > 0.0 && flat) {
            return finding("NATIVE_LEAK", "warn", Map.of("rss.slope", rss.slope()));
        }
        return null;
    }

    private static Map<String, Object> threadLeak(WindowSnapshot window) {
        if (window.liveThreads.size() < 3 || window.threadStarts <= 0.0) {
            return null;
        }
        Fit live = fit(window.liveThreads);
        if (live.slope() > 0.0 && window.threadStarts > 5.0 * Math.max(1.0, window.threadEnds)) {
            return finding("THREAD_LEAK", "warn", Map.of("thread.starts", window.threadStarts, "thread.ends", window.threadEnds));
        }
        return null;
    }

    private static Map<String, Object> exceptions(WindowSnapshot window) {
        double seconds = window.windowNs / 1_000_000_000.0;
        if (seconds <= 0.0) {
            return null;
        }
        double rate = window.throwables / seconds;
        if (rate > 10_000.0) {
            return finding("EXCEPTION_STORM", "warn", Map.of("exceptions.per_s", rate));
        }
        return null;
    }

    private static Map<String, Object> containerOom(WindowSnapshot window) {
        if (window.memLimit == null || window.memLimit <= 0.0) {
            return null;
        }
        double ratio = window.memoryUsage / window.memLimit;
        if (ratio > 0.9) {
            return finding("CONTAINER_OOM_RISK", "critical", Map.of("memory.ratio", ratio));
        }
        return null;
    }

    private static Map<String, Object> ergonomic(WindowSnapshot window) {
        if (window.ergonomicSerial) {
            return finding("ERGONOMIC_SERIAL_GC", "warn", Map.of());
        }
        return null;
    }

    private static double heapPct(WindowSnapshot window) {
        if (window.heapAfter.isEmpty() || window.heapMax == null || window.heapMax <= 0) {
            return 0.0;
        }
        return window.heapAfter.get(window.heapAfter.size() - 1) / window.heapMax * 100.0;
    }

    private static Double spike(List<Double> values) {
        if (values.size() < 2) {
            return null;
        }
        double baseline = median(values.subList(0, values.size() - 1));
        double last = values.get(values.size() - 1);
        if (baseline > 0.0 && last > 10.0 * baseline) {
            return last;
        }
        return null;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 0) {
            return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
        }
        return sorted.get(mid);
    }

    static Fit fit(List<Double> values) {
        int n = values.size();
        double sumX = 0.0;
        double sumY = 0.0;
        double sumXX = 0.0;
        double sumXY = 0.0;
        for (int i = 0; i < n; i++) {
            double y = values.get(i);
            sumX += i;
            sumY += y;
            sumXX += (double) i * i;
            sumXY += i * y;
        }
        double denom = n * sumXX - sumX * sumX;
        if (denom == 0.0) {
            return new Fit(0.0, 0.0);
        }
        double slope = (n * sumXY - sumX * sumY) / denom;
        double intercept = (sumY - slope * sumX) / n;
        double mean = sumY / n;
        double ssTot = 0.0;
        double ssRes = 0.0;
        for (int i = 0; i < n; i++) {
            double y = values.get(i);
            double predicted = intercept + slope * i;
            ssTot += (y - mean) * (y - mean);
            ssRes += (y - predicted) * (y - predicted);
        }
        double r2 = ssTot == 0.0 ? 1.0 : 1.0 - ssRes / ssTot;
        return new Fit(slope, r2);
    }

    private static void add(List<Map<String, Object>> findings, Map<String, Object> finding) {
        if (finding != null) {
            findings.add(finding);
        }
    }

    private static Map<String, Object> finding(String code, String severity, Map<String, Object> metrics) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("severity", severity);
        if (!metrics.isEmpty()) {
            body.put("metrics", metrics);
        }
        return body;
    }

    record Fit(double slope, double r2) {}
}

package io.jafra.analyzer.v2.rollup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One stored chunk, already read. Extractors do not touch JFR. */
public final class ChunkFacts {
    private static final Set<String> SPAN_TYPES = Set.of(
            "jdk.MethodTrace",
            "jdk.SocketRead",
            "jdk.SocketWrite",
            "jdk.JavaMonitorEnter",
            "jdk.ThreadPark",
            "jdk.GCPhasePause",
            "profiler.WallClockSample");

    public final List<Seen> events;
    public final double dtSeconds;
    public final long fromMs;
    public final long toMs;
    public final int jdkMajor;
    public final String jdkVersion;
    public final long jvmStartMs;
    public final String profile;
    public final String settingsJson;
    /** Event type name to active threshold in milliseconds. Missing means no threshold setting. */
    public final Map<String, Double> thresholdMs;
    /** UNVERIFIED lock-sampler interval, milliseconds. */
    public final double lockIntervalMs;
    /** Event types declared in the chunk. Filled by ChunkReader. */
    public Set<String> eventTypes = Set.of();
    /** False when the chunk has no jdk.JVMInformation. The writer then reuses a stored JVM row. */
    public boolean sawJvmInformation;

    private final Map<String, List<Seen>> byType;
    private final Map<String, List<Span>> spansByThread;

    public ChunkFacts(
            List<Seen> events,
            double dtSeconds,
            long fromMs,
            long toMs,
            int jdkMajor,
            String jdkVersion,
            long jvmStartMs,
            String profile,
            String settingsJson,
            Map<String, Double> thresholdMs,
            double lockIntervalMs) {
        this.events = events == null ? List.of() : events;
        this.dtSeconds = dtSeconds;
        this.fromMs = fromMs;
        this.toMs = toMs;
        this.jdkMajor = jdkMajor;
        this.jdkVersion = jdkVersion == null ? "" : jdkVersion;
        this.jvmStartMs = jvmStartMs;
        this.profile = profile == null ? "custom" : profile;
        this.settingsJson = settingsJson == null ? "{}" : settingsJson;
        this.thresholdMs = thresholdMs == null ? Map.of() : thresholdMs;
        this.lockIntervalMs = lockIntervalMs;
        Map<String, List<Seen>> grouped = new LinkedHashMap<>();
        Map<String, List<Span>> spans = new HashMap<>();
        int order = 0;
        for (Seen event : this.events) {
            grouped.computeIfAbsent(event.type, ignored -> new ArrayList<>()).add(event);
            if (SPAN_TYPES.contains(event.type)) {
                spans.computeIfAbsent(spanKey(event.type, event.thread), ignored -> new ArrayList<>())
                        .add(new Span(event, order, event.startMs, event.endMs));
            }
            order++;
        }
        Map<String, List<Seen>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, List<Seen>> entry : grouped.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.byType = Map.copyOf(frozen);
        for (List<Span> list : spans.values()) {
            list.sort(Comparator.comparingLong(Span::start).thenComparingInt(Span::order));
        }
        this.spansByThread = spans;
    }

    public List<Seen> ofType(String type) {
        List<Seen> found = byType.get(type);
        return found == null ? List.of() : found;
    }

    /**
     * Innermost span of {@code type} on the sample's thread that contains {@code sample.startMs}.
     * Innermost means the latest start; a tie keeps the later event in file order, which is what a
     * forward scan used to return.
     */
    public Seen enclosing(String type, Seen sample) {
        if (sample == null) {
            return null;
        }
        return enclosing(type, sample.thread, sample.startMs);
    }

    /**
     * True when a span of {@code type} contains the sample. A span with a blank thread matches any
     * sample, matching the previous scan.
     */
    public boolean overlaps(String type, Seen sample) {
        if (sample == null) {
            return false;
        }
        if (enclosing(type, sample.thread, sample.startMs) != null) {
            return true;
        }
        return !sample.thread.isBlank() && enclosing(type, "", sample.startMs) != null;
    }

    /** Wall samples on {@code thread} whose start falls inside {@code [startMs, endMs]}. */
    public List<Seen> wallsBetween(String thread, long startMs, long endMs) {
        List<Span> spans = spansByThread.get(spanKey("profiler.WallClockSample", thread == null ? "" : thread));
        if (spans == null || spans.isEmpty()) {
            return List.of();
        }
        int lo = lowerBound(spans, startMs);
        int hi = upperBound(spans, endMs);
        if (hi <= lo) {
            return List.of();
        }
        List<Seen> found = new ArrayList<>(hi - lo);
        for (int i = lo; i < hi; i++) {
            found.add(spans.get(i).event);
        }
        return found;
    }

    private Seen enclosing(String type, String thread, long at) {
        List<Span> spans = spansByThread.get(spanKey(type, thread == null ? "" : thread));
        if (spans == null || spans.isEmpty()) {
            return null;
        }
        int hi = upperBound(spans, at);
        Seen found = null;
        int bestOrder = -1;
        long lookback = lookback(spans);
        for (int i = hi - 1; i >= 0; i--) {
            Span span = spans.get(i);
            if (at - span.start > lookback) {
                break;
            }
            if (at <= span.end && span.order >= bestOrder) {
                found = span.event;
                bestOrder = span.order;
            }
        }
        return found;
    }

    private static long lookback(List<Span> spans) {
        long max = 0L;
        for (Span span : spans) {
            max = Math.max(max, span.end - span.start);
        }
        return Math.max(0L, max);
    }

    private static int upperBound(List<Span> spans, long at) {
        int lo = 0;
        int hi = spans.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (spans.get(mid).start <= at) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static int lowerBound(List<Span> spans, long at) {
        int lo = 0;
        int hi = spans.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (spans.get(mid).start < at) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static String spanKey(String type, String thread) {
        return type + "\0" + (thread == null ? "" : thread);
    }

    private record Span(Seen event, int order, long start, long end) {}

    public static ChunkFacts empty(double dtSeconds, long fromMs, long toMs, double lockIntervalMs) {
        return new ChunkFacts(
                List.of(), dtSeconds, fromMs, toMs, 0, "", 0L, "custom", "{}", Map.of(), lockIntervalMs);
    }

    public Map<Long, List<Seen>> byId(String type, String field) {
        Map<Long, List<Seen>> grouped = new LinkedHashMap<>();
        for (Seen event : ofType(type)) {
            long id = event.id(field);
            if (id < 0) {
                continue;
            }
            grouped.computeIfAbsent(id, ignored -> new ArrayList<>()).add(event);
        }
        return grouped;
    }
}

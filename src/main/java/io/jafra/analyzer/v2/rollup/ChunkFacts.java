package io.jafra.analyzer.v2.rollup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One stored chunk, already read. Extractors do not touch JFR. */
public final class ChunkFacts {
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
        this.events = events;
        this.dtSeconds = dtSeconds;
        this.fromMs = fromMs;
        this.toMs = toMs;
        this.jdkMajor = jdkMajor;
        this.jdkVersion = jdkVersion == null ? "" : jdkVersion;
        this.jvmStartMs = jvmStartMs;
        this.profile = profile == null ? "custom" : profile;
        this.settingsJson = settingsJson == null ? "{}" : settingsJson;
        this.thresholdMs = thresholdMs;
        this.lockIntervalMs = lockIntervalMs;
    }

    public List<Seen> ofType(String type) {
        List<Seen> found = new ArrayList<>();
        for (Seen event : events) {
            if (type.equals(event.type)) {
                found.add(event);
            }
        }
        return found;
    }

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

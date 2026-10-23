package io.jafra.analyzer.v2.incidents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.jafra.analyzer.v2.incidents.support.DetectorHistory;

/** In-memory history for detector tests. Does not touch DuckDB. */
final class ScriptedHistory extends DetectorHistory {
    private final Map<Long, List<Point>> byEnd = new LinkedHashMap<>();
    private List<Point> fallback = List.of();
    private Baseline baseline = new Baseline(1d, 0.1, 80, 80, null, java.util.Set.of());
    final List<String> recorded = new ArrayList<>();

    ScriptedHistory() {
        super(null, null);
    }

    ScriptedHistory until(long toMs, List<Point> points) {
        byEnd.put(toMs, points);
        return this;
    }

    ScriptedHistory fallback(List<Point> points) {
        this.fallback = points;
        return this;
    }

    ScriptedHistory baseline(Baseline baseline) {
        this.baseline = baseline;
        return this;
    }

    @Override
    public List<Point> points(int jvmId, String metric, String key, String stat, long fromMs, long toMs) {
        return byEnd.getOrDefault(toMs, fallback);
    }

    @Override
    public Baseline baseline(int jvmId, String name, String key, String group, long toMs) {
        return baseline;
    }

    @Override
    public Double firstValue(int jvmId, String metric, long afterMs) {
        return fallback.isEmpty() ? null : fallback.get(0).value();
    }

    @Override
    public void record(int jvmId, String name, String key, long ts, double value) {
        recorded.add(name + "|" + key + "|" + ts + "|" + value);
    }

    static List<Point> line(int n, long stepMs, double start, double step) {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            points.add(new Point(i * stepMs, start + i * step));
        }
        return points;
    }
}

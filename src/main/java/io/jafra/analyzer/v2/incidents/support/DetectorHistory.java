package io.jafra.analyzer.v2.incidents.support;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.v2.incidents.Baseline;
import io.jafra.analyzer.v2.incidents.BaselineService;
import io.jafra.analyzer.v2.store.V2Store;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;
import io.jafra.analyzer.v2.store.V2Store.TimedValue;

/** Reads catalogue rollups and internal series. Writes internal series for I8 and I11. */
@ApplicationScoped
public class DetectorHistory {
    public record Point(long ts, double value) {}

    private final V2Store store;
    private final BaselineService baselines;

    @Inject
    public DetectorHistory(V2Store store, BaselineService baselines) {
        this.store = store;
        this.baselines = baselines;
    }

    public List<Point> points(int jvmId, String metric, String key, String stat, long fromMs, long toMs) {
        try {
            if (metric != null && metric.startsWith("internal.")) {
                List<Point> points = new ArrayList<>();
                for (TimedValue row : store.readInternalPoints(jvmId, metric, key, fromMs, toMs)) {
                    points.add(new Point(row.ts(), row.value()));
                }
                return points;
            }
            List<Point> points = new ArrayList<>();
            for (RollupRow row : store.readRollup(false, jvmId, metric, fromMs, toMs, "", key == null ? "" : key)) {
                points.add(new Point(row.ts(), statOf(row, stat)));
            }
            return points;
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    public List<Long> lastWindows(int jvmId, int n, long throughMs) {
        try {
            return store.recentWindowStarts(jvmId, throughMs, n);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    public double sum(int jvmId, String metric, String key, String stat, int windows, long throughMs) {
        List<Long> starts = lastWindows(jvmId, windows, throughMs);
        if (starts.isEmpty()) {
            return 0d;
        }
        long from = starts.get(starts.size() - 1);
        long to = throughMs + 1;
        double total = 0d;
        for (Point point : points(jvmId, metric, key, stat, from, to)) {
            total += point.value();
        }
        return total;
    }

    public Baseline baseline(int jvmId, String name, String key, String group, long toMs) {
        try {
            return baselines.baseline(jvmId, name, key, group, "avg", toMs);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    public Double firstValue(int jvmId, String metric, long afterMs) {
        List<Point> points = points(jvmId, metric, "", "avg", afterMs, Long.MAX_VALUE);
        return points.isEmpty() ? null : points.get(0).value();
    }

    public void record(int jvmId, String name, String key, long ts, double value) {
        try {
            store.writeInternalSeries(jvmId, name, key, ts, value);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static double statOf(RollupRow row, String stat) {
        if ("min".equals(stat)) {
            return row.min();
        }
        if ("max".equals(stat)) {
            return row.max();
        }
        if ("sum".equals(stat)) {
            return row.sum();
        }
        if ("count".equals(stat)) {
            return row.count();
        }
        return row.avg();
    }
}

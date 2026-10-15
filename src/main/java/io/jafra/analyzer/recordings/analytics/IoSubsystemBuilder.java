package io.jafra.analyzer.recordings.analytics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.ItemFilters;

import io.jafra.analyzer.recordings.JfrItemAccess;
import io.jafra.analyzer.recordings.TimeBucketGrid;
import io.jafra.analyzer.recordings.stats.Percentiles;

final class IoSubsystemBuilder extends AnalyticsSubsystemSupport {
    private static final int TOP_TARGETS = 5;

    private static final List<String> EXPECTED = List.of(
            "jdk.FileRead", "jdk.FileWrite", "jdk.SocketRead", "jdk.SocketWrite", "jdk.ContainerIOUsage");

    private static final List<IoOp> OPS = List.of(
            new IoOp("fileRead", "jdk.FileRead", true, false),
            new IoOp("fileWrite", "jdk.FileWrite", false, false),
            new IoOp("socketRead", "jdk.SocketRead", true, true),
            new IoOp("socketWrite", "jdk.SocketWrite", false, true));

    @Override
    public String id() {
        return "io";
    }

    @Override
    public AnalyticsModels.SubsystemResult build(
            IItemCollection events,
            Set<String> available,
            TimeBucketGrid grid,
            AnalyticsModels.AnalyticsParams params,
            List<AnalyticsModels.Marker> markers) {
        var availability = avail(EXPECTED, available);
        if (availability.status().equals(AnalyticsModels.AVAIL_ABSENT)) {
            return absent(id(), EXPECTED);
        }
        Map<String, Object> rollup = new LinkedHashMap<>();
        List<Map<String, Object>> topTargets = new ArrayList<>();
        for (IoOp op : OPS) {
            if (!available.contains(op.typeId)) {
                continue;
            }
            OpAccumulator acc = collect(events, op);
            rollup.put(op.key, acc.toRollup());
            topTargets.addAll(acc.topTargets(op.key));
        }
        topTargets.sort(Comparator.comparingDouble((Map<String, Object> row) -> ((Number) row.get("totalMs")).doubleValue())
                .reversed());
        if (topTargets.size() > TOP_TARGETS) {
            topTargets = new ArrayList<>(topTargets.subList(0, TOP_TARGETS));
        }
        rollup.put("topTargets", topTargets);
        if (available.contains("jdk.ContainerIOUsage")) {
            long firstBytes = -1L;
            long lastBytes = -1L;
            for (IItemIterable iterable : events.apply(ItemFilters.type("jdk.ContainerIOUsage"))) {
                for (IItem item : iterable) {
                    long bytes = readQuantityBytes(item, "dataTransferred");
                    if (firstBytes < 0) {
                        firstBytes = bytes;
                    }
                    lastBytes = bytes;
                }
            }
            if (firstBytes >= 0 && lastBytes >= firstBytes) {
                rollup.put("containerIoBytesDelta", lastBytes - firstBytes);
            }
        }
        return new AnalyticsModels.SubsystemResult(
                id(),
                availability,
                rollup,
                null,
                Map.of(),
                List.of("File and socket reads and writes are counted separately."));
    }

    private static OpAccumulator collect(IItemCollection events, IoOp op) {
        OpAccumulator acc = new OpAccumulator(op.read);
        for (IItemIterable iterable : events.apply(ItemFilters.type(op.typeId))) {
            for (IItem item : iterable) {
                Long dur = JfrItemAccess.durationNs(item);
                double ms = dur == null ? 0.0 : dur / 1_000_000.0;
                long bytes = readQuantityBytes(item, op.read ? "bytesRead" : "bytesWritten");
                acc.add(target(item, op.socket), ms, bytes);
            }
        }
        return acc;
    }

    private static String target(IItem item, boolean socket) {
        if (!socket) {
            String path = JfrItemAccess.text(item, "path");
            return path == null ? "(unknown)" : path;
        }
        String host = JfrItemAccess.text(item, "host", "address");
        String port = JfrItemAccess.text(item, "port");
        if (host == null) {
            return "(unknown)";
        }
        if (port == null || port.isBlank()) {
            return host;
        }
        return host + ":" + port;
    }

    private record IoOp(String key, String typeId, boolean read, boolean socket) {}

    private static final class OpAccumulator {
        private final boolean read;
        private long count;
        private long bytes;
        private final List<Double> durationsMs = new ArrayList<>();
        private final Map<String, TargetAccumulator> targets = new LinkedHashMap<>();

        OpAccumulator(boolean read) {
            this.read = read;
        }

        void add(String target, double durationMs, long byteCount) {
            count++;
            bytes += Math.max(0L, byteCount);
            if (durationMs > 0.0) {
                durationsMs.add(durationMs);
            }
            targets.computeIfAbsent(target, ignored -> new TargetAccumulator()).add(durationMs, byteCount);
        }

        Map<String, Object> toRollup() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("count", count);
            row.put(read ? "bytesRead" : "bytesWritten", bytes);
            Percentiles.summarize(durationsMs).ifPresent(summary -> {
                row.put("p50Ms", summary.p50());
                row.put("p99Ms", summary.p99());
                row.put("maxMs", summary.max());
            });
            return row;
        }

        List<Map<String, Object>> topTargets(String kind) {
            List<Map.Entry<String, TargetAccumulator>> ranked = new ArrayList<>(targets.entrySet());
            ranked.sort(Comparator.comparingDouble((Map.Entry<String, TargetAccumulator> entry) -> entry.getValue().totalMs)
                    .reversed());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < ranked.size() && i < TOP_TARGETS; i++) {
                Map.Entry<String, TargetAccumulator> entry = ranked.get(i);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kind", kind);
                row.put("target", entry.getKey());
                row.put("count", entry.getValue().count);
                row.put("totalMs", entry.getValue().totalMs);
                row.put("bytes", entry.getValue().bytes);
                rows.add(row);
            }
            return rows;
        }
    }

    private static final class TargetAccumulator {
        long count;
        double totalMs;
        long bytes;

        void add(double durationMs, long byteCount) {
            count++;
            totalMs += durationMs;
            bytes += Math.max(0L, byteCount);
        }
    }
}

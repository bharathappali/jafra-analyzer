package io.jafra.analyzer.api.v2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.jafra.analyzer.findings.FindingDetectors;
import io.jafra.analyzer.findings.WindowSnapshot;
import io.jafra.analyzer.ingest.decode.ParquetTables;
import io.jafra.analyzer.metrics.MetricRegistry;
import io.jafra.analyzer.recordings.stats.Pearson;
import io.jafra.analyzer.signals.SignalRegistry;

/** Reads the v2 Parquet tables. */
public final class V2ReadService {
    private static final long LIVE_NS = 120_000_000_000L;
    private static final List<String> OVERVIEW = List.of(
            "cpu", "container", "gc", "heap", "alloc", "jit", "safepoint", "native_memory", "locks", "threads", "io",
            "network", "exceptions", "class_loading");

    private final Connection connection;
    private final Path root;

    public V2ReadService(Connection connection, Path root) {
        this.connection = connection;
        this.root = root;
    }

    public Map<String, Object> workloads(Instant now, boolean includeTombstoned) throws SQLException {
        List<InstanceRow> rows = new ArrayList<>();
        String glob = files("jvm_instance", "");
        if (glob != null) {
            String sql = "SELECT namespace, pod, container, jvm_instance_id, "
                    + "arg_max(pod_uid, t), arg_max(instance_seq, t), arg_max(jvm_start, t), arg_max(pid, t), "
                    + "arg_max(jdk_version, t), arg_max(restart_reason_hint, t), min(t), max(t) "
                    + "FROM read_parquet('" + glob + "', hive_partitioning=false, union_by_name=true) GROUP BY 1, 2, 3, 4";
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
                while (result.next()) {
                    rows.add(new InstanceRow(
                            result.getString(1), result.getString(2), result.getString(3), result.getString(4),
                            result.getString(5), result.getInt(6), result.getLong(7), result.getLong(8),
                            result.getString(9), result.getString(10), result.getLong(11), result.getLong(12)));
                }
            }
        }
        return tree(rows, now.toEpochMilli() * 1_000_000L);
    }

    public String latestInstance(String ns, String pod, String container) throws SQLException {
        String glob = files("jvm_instance", partition(ns, pod, container, null));
        if (glob == null) {
            return null;
        }
        String sql = "SELECT jvm_instance_id FROM read_parquet('" + glob + "', hive_partitioning=false, union_by_name=true) "
                + "ORDER BY jvm_start DESC, t DESC LIMIT 1";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    public Map<String, Object> instance(Identity id) throws SQLException {
        Map<String, Object> response = identity(id);
        String instances = glob("jvm_instance", id);
        if (instances != null) {
            String sql = "SELECT "
                    + arg("jdk_version") + ", " + arg("jdk_feature") + ", " + arg("pid") + ", " + arg("jvm_start") + ", "
                    + arg("gc_young") + ", " + arg("gc_old") + ", " + arg("gc_selected_by") + ", " + arg("effective_cpus") + ", "
                    + arg("cpu_limit_cores") + ", " + arg("cpu_request_millicores_approx") + ", " + arg("cpu_request_quality") + ", "
                    + arg("cpu_qos") + ", " + arg("mem_limit") + ", " + arg("container_type")
                    + " FROM read_parquet('" + instances + "', hive_partitioning=false, union_by_name=true)";
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
                if (result.next()) {
                    Map<String, Object> jvm = new LinkedHashMap<>();
                    put(jvm, "jdk", result.getString(1));
                    put(jvm, "jdk_feature", result.getObject(2));
                    put(jvm, "pid", result.getObject(3));
                    put(jvm, "jvm_start", result.getObject(4));
                    put(jvm, "gc_young", result.getString(5));
                    put(jvm, "gc_old", result.getString(6));
                    put(jvm, "gc_selected_by", result.getString(7));
                    put(jvm, "effective_cpus", result.getObject(8));
                    put(jvm, "cpu_limit_cores", result.getObject(9));
                    put(jvm, "cpu_request_millicores_approx", result.getObject(10));
                    put(jvm, "cpu_request_quality", result.getString(11));
                    put(jvm, "cpu_qos", result.getString(12));
                    put(jvm, "mem_limit", result.getObject(13));
                    put(jvm, "container_type", result.getString(14));
                    response.put("jvm", jvm);
                }
            }
        }
        List<Map<String, Object>> signals = new ArrayList<>();
        for (ReadSignal signal : signalState(id).values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", signal.id());
            row.put("status", signal.status());
            put(row, "reason", signal.reason());
            if (signal.minJdk() != null) {
                row.put("min_jdk", signal.minJdk());
            }
            put(row, "hint", signal.hint());
            signals.add(row);
        }
        response.put("signals", signals);
        response.put("findings", findings(id));
        return response;
    }

    public Map<String, Object> series(Identity id, V2Window.Range range, String requestedStep, String metrics, boolean compact)
            throws SQLException {
        return series(id, range, requestedStep, metrics, compact, 240);
    }

    public Map<String, Object> series(Identity id, V2Window.Range range, String requestedStep, String metrics, boolean compact,
            int maxPoints) throws SQLException {
        List<MetricRef> refs = metrics(metrics);
        String nativePeriod = coarsestNative(refs);
        V2Window.StepChoice choice = V2Window.resolveStep(requestedStep, range, nativePeriod, maxPoints);
        long start = Math.floorDiv(range.fromNs(), choice.step().nanos) * choice.step().nanos;
        long end = range.toNs();
        List<Long> offsets = new ArrayList<>();
        List<Long> buckets = new ArrayList<>();
        for (long bucket = start; bucket < end && offsets.size() < maxPoints; bucket += choice.step().nanos) {
            buckets.add(bucket);
            offsets.add((bucket - start) / 1_000_000_000L);
        }
        Map<String, Object> series = new LinkedHashMap<>();
        Map<String, ReadSignal> signals = signalState(id);
        boolean any = false;
        for (MetricRef ref : refs) {
            Map<Long, Double> values = rollup(id, choice.step(), ref, start, end);
            List<Object> points = new ArrayList<>();
            boolean seen = false;
            for (Long bucket : buckets) {
                Double value = values.get(bucket);
                if (value == null) {
                    points.add(null);
                } else {
                    seen = true;
                    any = true;
                    points.add(number(value, ref.unit(), compact));
                }
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("unit", ref.unit());
            ReadSignal signal = signals.get(SignalRegistry.get().metricSignal(ref.id()));
            if (!seen && signal != null && "unavailable".equals(signal.status())) {
                body.put("status", "unavailable");
                put(body, "reason", signal.reason());
                if (signal.minJdk() != null) {
                    body.put("min_jdk", signal.minJdk());
                }
                put(body, "hint", signal.hint());
            } else {
                body.put("status", seen ? "ok" : "no_data_in_window");
                if (seen) {
                    body.put("v", points);
                }
            }
            series.put(ref.name(), body);
        }
        Map<String, Object> response = identity(id);
        response.put("step", choice.step().label);
        response.put("status", choice.coarserThanRequested() ? "coarser_than_requested" : any ? "ok" : "no_data_in_window");
        response.put("t0", start / 1_000_000L);
        response.put("t", offsets);
        response.put("series", series);
        return response;
    }

    public Map<String, Object> top(Identity id, V2Window.Range range, String kind, String group, int limit, V2Window.Range compare,
            boolean compact) throws SQLException {
        if ("thread_group".equals(group) || "native".equals(kind)) {
            Map<String, Object> empty = identity(id);
            empty.put("samples", 0);
            empty.put("status", "no_data_in_window");
            empty.put("rows", List.of());
            return empty;
        }
        List<TopRow> rows = ranks(id, range, kind, group);
        long samples = rows.stream().mapToLong(TopRow::n).sum();
        double total = rows.stream().mapToDouble(TopRow::weight).sum();
        Map<String, TopRow> baseline = compare == null ? Map.of() : index(ranks(id, compare, kind, group));
        rows.sort(Comparator.comparingDouble(TopRow::weight).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        double shown = 0.0;
        int cap = Math.min(Math.max(limit, 1), 50);
        for (TopRow row : rows) {
            if (out.size() >= cap) {
                break;
            }
            double share = total <= 0.0 ? 0.0 : row.weight() / total;
            shown += share;
            Map<String, Object> item = new LinkedHashMap<>();
            String key = "stack".equals(group) ? row.stackId() : row.key();
            item.put("key", key);
            if (row.key() != null && !row.key().equals(key)) {
                item.put("label", row.key());
            }
            item.put("share", CompactNumbers.round(share, compact));
            item.put("ci95", CompactNumbers.round(ci(share, samples), compact));
            item.put("value", CompactNumbers.round(row.weight(), compact));
            if (row.stackId() != null) {
                item.put("stack", row.stackId());
            }
            TopRow before = baseline.get(key);
            if (compare != null) {
                double beforeShare = before == null || before.weight() <= 0.0 ? 0.0 : before.weight() / Math.max(1e-9, baselineWeight(baseline));
                item.put("before", CompactNumbers.round(beforeShare, compact));
                item.put("after", CompactNumbers.round(share, compact));
                item.put("z", CompactNumbers.round(z(share, samples, beforeShare, before == null ? 0 : before.n()), compact));
            }
            out.add(item);
        }
        Map<String, Object> response = identity(id);
        response.put("samples", samples);
        response.put("status", samples < 300 ? "insufficient_samples" : "ok");
        response.put("rows", out);
        response.put("others", CompactNumbers.round(Math.max(0.0, 1.0 - shown), compact));
        return response;
    }

    public Map<String, Object> events(Identity id, V2Window.Range range, String type, Double minMs, String cursor, int limit)
            throws SQLException {
        int cap = Math.min(Math.max(limit, 1), 500);
        String table = eventTable(type);
        if (table == null) {
            Map<String, Object> empty = identity(id);
            empty.put("status", "no_data_in_window");
            empty.put("rows", List.of());
            return empty;
        }
        String glob = glob(table, id);
        Map<String, Object> response = identity(id);
        if (glob == null) {
            response.put("status", "no_data_in_window");
            response.put("rows", List.of());
            return response;
        }
        long after = cursor == null || cursor.isBlank() ? range.fromNs() - 1 : Long.parseLong(cursor);
        String timeColumn = "t";
        String durColumn = "gc".equals(table) ? "sum_pause_ms" : "gc_phase".equals(table) ? "dur_ms" : "dur_ms";
        String extra = eventFilter(type);
        String min = minMs == null ? "" : " AND " + durColumn + " >= " + minMs;
        String sql = "SELECT t, " + eventLabel(type) + ", " + durColumn + ", " + eventTarget(type)
                + " FROM read_parquet('" + glob + "', hive_partitioning=false, union_by_name=true) WHERE t >= " + range.fromNs()
                + " AND t < " + range.toNs() + " AND t > " + after + extra + min + " ORDER BY t LIMIT " + (cap + 1);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("t", result.getLong(1));
                row.put("type", result.getString(2));
                put(row, "dur_ms", result.getObject(3) == null ? null : result.getDouble(3));
                put(row, "target", result.getString(4));
                rows.add(row);
            }
        }
        String next = null;
        if (rows.size() > cap) {
            rows = new ArrayList<>(rows.subList(0, cap));
            next = String.valueOf(rows.get(rows.size() - 1).get("t"));
        }
        response.put("status", rows.isEmpty() ? "no_data_in_window" : "ok");
        response.put("rows", rows);
        put(response, "next_cursor", next);
        return response;
    }

    public Map<String, Object> markers(Identity id, V2Window.Range range, String kinds, String minSeverity) throws SQLException {
        String glob = glob("marker", id);
        Map<String, Object> response = identity(id);
        if (glob == null) {
            response.put("status", "no_data_in_window");
            response.put("markers", List.of());
            return response;
        }
        int floor = severity(minSeverity == null ? "info" : minSeverity);
        String sql = "SELECT t, t_end, kind, severity, magnitude, unit, ref FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE t >= " + range.fromNs() + " AND t < " + range.toNs() + " ORDER BY t";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                String kind = result.getString(3);
                if (kinds != null && !kinds.isBlank() && !List.of(kinds.split(",")).contains(kind)) {
                    continue;
                }
                if (severity(result.getString(4)) < floor) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("t", result.getLong(1));
                put(row, "t_end", result.getObject(2) == null ? null : result.getLong(2));
                row.put("kind", kind);
                row.put("severity", result.getString(4));
                put(row, "magnitude", result.getObject(5) == null ? null : result.getDouble(5));
                put(row, "unit", result.getString(6));
                put(row, "ref", result.getString(7));
                rows.add(row);
            }
        }
        if (rows.size() > 500) {
            response.put("status", "ok");
            response.put("clustered", true);
            response.put("buckets", cluster(rows));
            return response;
        }
        response.put("status", rows.isEmpty() ? "no_data_in_window" : "ok");
        response.put("markers", rows);
        return response;
    }

    public Map<String, Object> stack(Identity id, String stackId, int depth) throws SQLException {
        if (depth < 1) {
            throw new IllegalArgumentException("depth must be at least 1");
        }
        String stacks = glob("stack", id);
        String frames = glob("frame", id);
        if (stacks == null || frames == null) {
            return null;
        }
        String sql = "SELECT frames, depth FROM read_parquet('" + stacks + "', hive_partitioning=false, union_by_name=true) WHERE stack_id = '"
                + escape(stackId) + "' LIMIT 1";
        String joined;
        int depthTotal;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                return null;
            }
            joined = result.getString(1);
            depthTotal = result.getInt(2);
        }
        List<String> ids = joined == null || joined.isBlank() ? List.of() : List.of(joined.split(","));
        int limit = Math.min(depth, ids.size());
        List<Map<String, Object>> out = new ArrayList<>();
        if (limit > 0) {
            String in = ids.subList(0, limit).stream().map(value -> "'" + escape(value) + "'").reduce((a, b) -> a + "," + b).orElse("''");
            Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
            String frameSql = "SELECT frame_id, \"class\", method, category, line FROM read_parquet('" + frames
                    + "', hive_partitioning=false, union_by_name=true) WHERE frame_id IN (" + in + ")";
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(frameSql)) {
                while (result.next()) {
                    String className = result.getString(2);
                    String method = result.getString(3);
                    String label = className == null || className.isBlank() ? method : className + "." + method;
                    Map<String, Object> frame = new LinkedHashMap<>();
                    frame.put("label", label);
                    frame.put("category", result.getString(4));
                    frame.put("line", result.getInt(5));
                    byId.put(result.getString(1), frame);
                }
            }
            for (int i = 0; i < limit; i++) {
                Map<String, Object> frame = byId.get(ids.get(i));
                if (frame != null) {
                    out.add(frame);
                }
            }
        }
        Map<String, Object> response = identity(id);
        response.put("stack_id", stackId);
        response.put("frames", out);
        response.put("depth_total", depthTotal);
        return response;
    }

    public Map<String, Object> compare(Identity id, V2Window.Range range, V2Window.Range baseline, String metrics, boolean compact)
            throws SQLException {
        Map<String, Object> response = identity(id);
        Map<String, Object> values = new LinkedHashMap<>();
        for (MetricRef ref : metrics(metrics)) {
            double current = mean(id, range, ref);
            double base = mean(id, baseline, ref);
            if (Double.isNaN(current) && Double.isNaN(base)) {
                Map<String, Object> missing = new LinkedHashMap<>();
                missing.put("status", "no_data_in_window");
                values.put(ref.name(), missing);
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("status", "ok");
            row.put("unit", ref.unit());
            row.put("value", number(current, ref.unit(), compact));
            row.put("baseline", number(base, ref.unit(), compact));
            if (!Double.isNaN(current) && !Double.isNaN(base) && base != 0.0) {
                row.put("delta_pct", CompactNumbers.round((current - base) / Math.abs(base) * 100.0, compact));
            }
            values.put(ref.name(), row);
        }
        response.put("metrics", values);
        return response;
    }

    public Map<String, Object> correlate(Identity id, V2Window.Range range, String a, String b) throws SQLException {
        List<MetricRef> leftRef = metrics(a);
        List<MetricRef> rightRef = metrics(b);
        if (leftRef.size() != 1 || rightRef.size() != 1) {
            throw new IllegalArgumentException("correlate takes one metric on each side");
        }
        TreeMap<Long, Double> left = rollup(id, V2Window.Step.M1, leftRef.get(0), range.fromNs(), range.toNs());
        TreeMap<Long, Double> right = rollup(id, V2Window.Step.M1, rightRef.get(0), range.fromNs(), range.toNs());
        Map<String, Object> response = identity(id);
        response.put("a", a);
        response.put("b", b);
        if (left.isEmpty() && right.isEmpty()) {
            unavailable(response, id, leftRef.get(0).id());
            return response;
        }
        java.util.TreeSet<Long> keys = new java.util.TreeSet<>();
        keys.addAll(left.keySet());
        keys.addAll(right.keySet());
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (Long key : keys) {
            xs.add(left.get(key));
            ys.add(right.get(key));
        }
        Map<String, Object> stats = Pearson.bestLag(xs, ys);
        response.put("status", stats.get("status"));
        response.put("n", stats.get("n"));
        if (stats.get("r") instanceof Number r) {
            response.put("r", CompactNumbers.round(r.doubleValue(), true));
            response.put("lag", stats.get("lagBuckets"));
        }
        return response;
    }

    private void unavailable(Map<String, Object> response, Identity id, String metric) throws SQLException {
        ReadSignal signal = signalState(id).get(SignalRegistry.get().metricSignal(metric));
        if (signal != null && "unavailable".equals(signal.status())) {
            response.put("status", "unavailable");
            put(response, "reason", signal.reason());
            if (signal.minJdk() != null) {
                response.put("min_jdk", signal.minJdk());
            }
            put(response, "hint", signal.hint());
            return;
        }
        response.put("status", "no_data_in_window");
    }

    public Map<String, Object> overview(Identity id, V2Window.Range range, boolean compact) throws SQLException {
        Map<String, ReadSignal> signals = signalState(id);
        List<String> codes = findings(id);
        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("cpu", block(headline(id, range, "cpu.jvm_user", "last", compact), null, List.of()));
        blocks.put("container", block(headline(id, range, "container.memory_usage", "last", compact), signals.get("container"), List.of()));
        blocks.put("gc", block(headline(id, range, "gc.pause_ms", "p99", compact), null, codes));
        blocks.put("heap", block(heapHeadline(id, range, compact), null, List.of()));
        blocks.put("alloc", block(headline(id, range, "alloc.bytes", "sum", compact), null, List.of()));
        blocks.put("jit", block(headline(id, range, "compile.ms", "sum", compact), null, List.of()));
        blocks.put("safepoint", block(headline(id, range, "safepoint.ms", "p99", compact), null, List.of()));
        blocks.put("native_memory", block(Map.of(), signals.get("nmt"), List.of()));
        blocks.put("locks", block(headline(id, range, "lock.enter_ms", "p99", compact), null, List.of()));
        blocks.put("threads", block(headline(id, range, "threads.live", "last", compact), null, List.of()));
        blocks.put("io", block(headline(id, range, "io.socket_read_ms", "p99", compact), signals.get("io.socket_read"), List.of()));
        blocks.put("network", Map.of("status", "no_data_in_window"));
        blocks.put("exceptions", block(headline(id, range, "exceptions.throwables", "sum", compact), null, List.of()));
        blocks.put("class_loading", block(headline(id, range, "classes.loaded", "sum", compact), null, List.of()));
        Map<String, Object> response = identity(id);
        response.put("blocks", blocks);
        return response;
    }

    public Map<String, Object> heap(Identity id, V2Window.Range range, boolean compact) throws SQLException {
        Map<String, Object> response = identity(id);
        String glob = glob("heap_gc", id);
        if (glob == null) {
            response.put("status", "no_data_in_window");
            response.put("collections", List.of());
            return response;
        }
        String sql = "SELECT t, gc_id, name, cause, kind, collector, estimated, heap_before, heap_after, committed, "
                + "eden_before, eden_after, survivor_before, survivor_after, old_before, old_after, young_capacity, "
                + "humongous_regions, promoted_bytes, tenuring_threshold FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE t >= " + range.fromNs() + " AND t < " + range.toNs()
                + " ORDER BY t LIMIT 501";
        List<Map<String, Object>> collections = new ArrayList<>();
        String collector = null;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("t", result.getLong(1));
                put(row, "gc_id", result.getObject(2));
                put(row, "name", result.getString(3));
                put(row, "cause", result.getString(4));
                put(row, "kind", result.getString(5));
                if (collector == null) {
                    collector = result.getString(6);
                }
                put(row, "collector", collector);
                row.put("estimated", result.getBoolean(7));
                Map<String, Object> before = generations(result, 8, 11, 13, 15, compact);
                Map<String, Object> after = generations(result, 9, 12, 14, 16, compact);
                if (!before.isEmpty()) {
                    row.put("before", before);
                }
                if (!after.isEmpty()) {
                    row.put("after", after);
                }
                put(row, "committed", bytes(result.getObject(10), compact));
                put(row, "young_capacity", bytes(result.getObject(17), compact));
                put(row, "humongous_regions", result.getObject(18));
                put(row, "promoted_bytes", bytes(result.getObject(19), compact));
                put(row, "tenuring_threshold", result.getObject(20));
                collections.add(row);
            }
        }
        boolean truncated = collections.size() > 500;
        if (truncated) {
            collections = new ArrayList<>(collections.subList(0, 500));
        }
        response.put("status", collections.isEmpty() ? "no_data_in_window" : "ok");
        if (truncated) {
            response.put("truncated", true);
        }
        put(response, "collector", collector);
        response.put("collections", collections);
        response.put("regions", regionFrames(id, range));
        return response;
    }

    private Map<String, Object> heapHeadline(Identity id, V2Window.Range range, boolean compact) throws SQLException {
        String glob = glob("heap_gc", id);
        if (glob == null) {
            return Map.of();
        }
        String sql = "SELECT arg_max(heap_after, t) FILTER (WHERE heap_after IS NOT NULL) FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE t >= " + range.fromNs() + " AND t < " + range.toNs();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next() || result.getObject(1) == null) {
                return Map.of();
            }
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("heap.after", bytes(result.getObject(1), compact));
            return metrics;
        }
    }

    private Map<String, Object> regionFrames(Identity id, V2Window.Range range) throws SQLException {
        String glob = glob("g1_region_frame", id);
        Map<String, Object> body = new LinkedHashMap<>();
        if (glob == null) {
            body.put("status", "no_data_in_window");
            return body;
        }
        String sql = "SELECT t, gc_id, type, region_count, used FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE t >= " + range.fromNs() + " AND t < " + range.toNs()
                + " ORDER BY t LIMIT 500";
        List<Map<String, Object>> frames = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("t", result.getLong(1));
                put(frame, "gc_id", result.getObject(2));
                frame.put("type", result.getString(3));
                frame.put("count", result.getLong(4));
                frame.put("used", result.getLong(5));
                frames.add(frame);
            }
        }
        body.put("status", frames.isEmpty() ? "no_data_in_window" : "ok");
        if (!frames.isEmpty()) {
            body.put("frames", frames);
        }
        return body;
    }

    public Map<String, Object> heapSummary(Identity id, V2Window.Range range) throws SQLException {
        Map<String, Object> response = identity(id);
        String glob = glob("heap_gc", id);
        if (glob == null) {
            response.put("status", "no_data_in_window");
            return response;
        }
        String sql = "SELECT kind, t, heap_after, promoted_bytes FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE t >= " + range.fromNs() + " AND t < " + range.toNs()
                + " ORDER BY t";
        Map<String, Long> counts = new LinkedHashMap<>();
        Double first = null;
        Double last = null;
        long firstT = 0L;
        long lastT = 0L;
        double promoted = 0.0;
        int n = 0;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                n++;
                String kind = result.getString(1) == null ? "unknown" : result.getString(1);
                counts.merge(kind, 1L, Long::sum);
                if (result.getObject(3) != null) {
                    double after = result.getDouble(3);
                    if (first == null) {
                        first = after;
                        firstT = result.getLong(2);
                    }
                    last = after;
                    lastT = result.getLong(2);
                }
                if (result.getObject(4) != null) {
                    promoted += result.getDouble(4);
                }
            }
        }
        if (n == 0) {
            response.put("status", "no_data_in_window");
            return response;
        }
        response.put("status", "ok");
        response.put("collections", n);
        response.put("counts", counts);
        if (first != null && last != null) {
            response.put("floor_first", Math.round(first));
            response.put("floor_last", Math.round(last));
            double hours = (lastT - firstT) / 3_600_000_000_000.0;
            if (hours > 0.0) {
                response.put("floor_slope_per_h", CompactNumbers.round((last - first) / hours, true));
            }
        }
        response.put("promoted_bytes", Math.round(promoted));
        double seconds = Math.max(1.0, (range.toNs() - range.fromNs()) / 1_000_000_000.0);
        response.put("promotion_per_s", CompactNumbers.round(promoted / seconds, true));
        return response;
    }

    public Map<String, Object> findings(Identity id, V2Window.Range range) throws SQLException {
        WindowSnapshot snapshot = new WindowSnapshot();
        snapshot.windowNs = Math.max(0L, range.toNs() - range.fromNs());
        loadRollups(id, range, snapshot);
        loadHeapFindings(id, range, snapshot);
        loadMarkerFindings(id, range, snapshot);
        loadTopFindings(id, range, snapshot);
        loadLimitFindings(id, snapshot);
        if (findings(id).contains("ERGONOMIC_SERIAL_GC")) {
            snapshot.ergonomicSerial = true;
            snapshot.observed = true;
        }
        Map<String, Object> response = identity(id);
        response.put("t_from", range.fromNs());
        response.put("t_to", range.toNs());
        if (!snapshot.observed) {
            response.put("status", "no_data_in_window");
            response.put("findings", List.of());
            return response;
        }
        response.put("status", "ok");
        response.put("findings", FindingDetectors.evaluate(snapshot));
        return response;
    }

    private void loadRollups(Identity id, V2Window.Range range, WindowSnapshot snapshot) throws SQLException {
        String glob = glob("rollup_1m", id);
        if (glob == null) {
            return;
        }
        String sql = "SELECT metric, CAST(bucket_t AS BIGINT), sum, n, p99, last FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE CAST(bucket_t AS BIGINT) >= " + range.fromNs()
                + " AND CAST(bucket_t AS BIGINT) < " + range.toNs() + " ORDER BY 2";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                snapshot.observed = true;
                String metric = result.getString(1);
                double sum = result.getDouble(3);
                double n = result.getDouble(4);
                double p99 = result.getDouble(5);
                double last = result.getDouble(6);
                switch (metric) {
                    case "gc.pause_ms" -> snapshot.pauseMs += sum;
                    case "alloc.bytes" -> {
                        snapshot.allocBytes += sum;
                        snapshot.allocPerSec.add(sum / 60.0);
                    }
                    case "cpu.jvm_user" -> snapshot.jvmCores = last;
                    case "container.throttled_slices" -> snapshot.throttledSlices += sum;
                    case "container.elapsed_slices" -> snapshot.elapsedSlices += sum;
                    case "container.memory_usage" -> snapshot.memoryUsage = last;
                    case "exceptions.throwables" -> snapshot.throwables += sum;
                    case "classes.loaded" -> snapshot.classesLoaded += sum;
                    case "classes.unloaded" -> snapshot.classesUnloaded += sum;
                    case "safepoint.ms" -> {
                        snapshot.safepointMs += sum;
                        snapshot.safepointCount += (long) n;
                    }
                    case "deopt.ms" -> snapshot.deoptCount += (long) n;
                    case "lock.enter_ms" -> snapshot.lockMs += sum;
                    case "rss" -> snapshot.rss.add(last);
                    case "threads.live" -> snapshot.liveThreads.add(last);
                    case "io.socket_read_ms" -> snapshot.socketP99.add(p99);
                    case "io.file_read_ms" -> snapshot.fileP99.add(p99);
                    default -> {
                    }
                }
            }
        }
    }

    private void loadHeapFindings(Identity id, V2Window.Range range, WindowSnapshot snapshot) throws SQLException {
        String glob = glob("heap_gc", id);
        if (glob == null) {
            return;
        }
        String sql = "SELECT kind, collector, cause, heap_after, committed, survivor_after, young_capacity, tenuring_threshold, "
                + "humongous_regions, promoted_bytes FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE t >= " + range.fromNs() + " AND t < " + range.toNs()
                + " ORDER BY t";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                snapshot.observed = true;
                String kind = result.getString(1);
                if (snapshot.collector == null) {
                    snapshot.collector = result.getString(2);
                }
                String cause = result.getString(3);
                if (cause != null && cause.toLowerCase().contains("humongous")) {
                    snapshot.humongousCause = true;
                }
                if (cause != null && cause.toLowerCase().contains("metadata")) {
                    snapshot.metadataGcCause = true;
                }
                if ("young".equals(kind) || "z_young".equals(kind)) {
                    snapshot.youngGcs++;
                }
                if (result.getObject(4) != null) {
                    snapshot.heapAfter.add(result.getDouble(4));
                }
                if (result.getObject(5) != null) {
                    snapshot.heapCommitted.add(result.getDouble(5));
                    snapshot.heapMax = snapshot.heapMax == null ? result.getLong(5) : Math.max(snapshot.heapMax, result.getLong(5));
                }
                if (result.getObject(6) != null && result.getObject(7) != null && result.getDouble(7) > 0.0
                        && result.getDouble(6) >= 0.9 * result.getDouble(7)) {
                    snapshot.survivorFullGcs++;
                }
                if (result.getObject(8) != null && result.getInt(8) <= 2) {
                    snapshot.lowTenuringGcs++;
                }
                if (result.getObject(9) != null) {
                    snapshot.humongousRegions = Math.max(snapshot.humongousRegions, result.getLong(9));
                }
                if (result.getObject(10) != null) {
                    snapshot.promotedBytes += result.getDouble(10);
                }
            }
        }
        String regions = glob("g1_region_frame", id);
        if (regions == null) {
            return;
        }
        String countSql = "SELECT coalesce(sum(region_count), 0) FROM read_parquet('" + regions
                + "', hive_partitioning=false, union_by_name=true) WHERE t >= " + range.fromNs() + " AND t < " + range.toNs();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(countSql)) {
            if (result.next()) {
                snapshot.regionCount = result.getLong(1);
            }
        }
    }

    private void loadMarkerFindings(Identity id, V2Window.Range range, WindowSnapshot snapshot) throws SQLException {
        String glob = glob("marker", id);
        if (glob == null) {
            return;
        }
        String sql = "SELECT kind, count(*) FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE t >= " + range.fromNs() + " AND t < " + range.toNs()
                + " GROUP BY 1";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                snapshot.observed = true;
                String kind = result.getString(1);
                long count = result.getLong(2);
                if ("evac_failure".equals(kind) || "promotion_failed".equals(kind)) {
                    snapshot.evacuationFailures += count;
                } else if ("allocation_stall".equals(kind)) {
                    snapshot.allocationStalls += count;
                } else if ("code_cache_full".equals(kind)) {
                    snapshot.codeCacheFull = true;
                }
            }
        }
    }

    private void loadTopFindings(Identity id, V2Window.Range range, WindowSnapshot snapshot) throws SQLException {
        String glob = glob("top_1m", id);
        if (glob == null) {
            return;
        }
        String sql = "SELECT kind, grp, \"key\", sum(weight), sum(n) FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE t >= " + range.fromNs() + " AND t < " + range.toNs()
                + " GROUP BY 1, 2, 3";
        double cpuWeight = 0.0;
        double cpuMax = 0.0;
        long cpuSamples = 0L;
        double lockWeight = 0.0;
        double lockMax = 0.0;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                snapshot.observed = true;
                double weight = result.getDouble(4);
                long n = result.getLong(5);
                if ("cpu".equals(result.getString(1)) && "method".equals(result.getString(2))) {
                    cpuWeight += weight;
                    cpuMax = Math.max(cpuMax, weight);
                    cpuSamples += n;
                }
                if ("lock".equals(result.getString(1)) && "monitor".equals(result.getString(2))) {
                    lockWeight += weight;
                    lockMax = Math.max(lockMax, weight);
                }
            }
        }
        if (cpuWeight > 0.0) {
            snapshot.topMethodShare = cpuMax / cpuWeight;
            snapshot.topSamples = cpuSamples;
        }
        if (lockWeight > 0.0) {
            snapshot.monitorShare = lockMax / lockWeight;
        }
    }

    private void loadLimitFindings(Identity id, WindowSnapshot snapshot) throws SQLException {
        String glob = glob("jvm_instance", id);
        if (glob == null) {
            return;
        }
        String sql = "SELECT "
                + "arg_max(mem_limit, t) FILTER (WHERE mem_limit IS NOT NULL), "
                + "arg_max(effective_cpus, t) FILTER (WHERE effective_cpus IS NOT NULL), "
                + "arg_max(cpu_limit_cores, t) FILTER (WHERE cpu_limit_cores IS NOT NULL) "
                + "FROM read_parquet('" + glob + "', hive_partitioning=false, union_by_name=true)";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                return;
            }
            if (result.getObject(1) != null) {
                snapshot.memLimit = result.getDouble(1);
            }
            if (result.getObject(2) != null) {
                snapshot.effectiveCpus = result.getDouble(2);
            }
            if (result.getObject(3) != null) {
                snapshot.cpuLimitCores = result.getDouble(3);
            }
        }
        if (snapshot.effectiveCpus != null && snapshot.effectiveCpus > 0.0 && snapshot.jvmCores <= 1.5) {
            snapshot.jvmCores = snapshot.jvmCores * snapshot.effectiveCpus;
        }
    }

    private static Map<String, Object> generations(ResultSet result, int heap, int eden, int survivor, int old, boolean compact)
            throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>();
        put(values, "heap", bytes(result.getObject(heap), compact));
        put(values, "eden", bytes(result.getObject(eden), compact));
        put(values, "survivor", bytes(result.getObject(survivor), compact));
        put(values, "old", bytes(result.getObject(old), compact));
        return values;
    }

    private static Object bytes(Object value, boolean compact) {
        if (!(value instanceof Number number)) {
            return null;
        }
        return number(number.doubleValue(), "bytes", compact);
    }

    private Map<String, Object> block(Map<String, Object> metrics, ReadSignal signal, List<String> findings) {
        Map<String, Object> block = new LinkedHashMap<>();
        if (metrics.isEmpty()) {
            if (signal != null && "unavailable".equals(signal.status())) {
                block.put("status", "unavailable");
                put(block, "reason", signal.reason());
                if (signal.minJdk() != null) {
                    block.put("min_jdk", signal.minJdk());
                }
                put(block, "hint", signal.hint());
                return block;
            }
            block.put("status", "no_data_in_window");
            if (findings.contains("ERGONOMIC_SERIAL_GC")) {
                block.put("flags", List.of("ERGONOMIC_SERIAL_GC"));
            }
            return block;
        }
        block.put("status", "ok");
        block.put("headline_metrics", metrics);
        if (findings.contains("ERGONOMIC_SERIAL_GC")) {
            block.put("flags", List.of("ERGONOMIC_SERIAL_GC"));
        }
        return block;
    }

    private Map<String, Object> headline(Identity id, V2Window.Range range, String metric, String column, boolean compact)
            throws SQLException {
        MetricRef ref = metric(metric, column);
        TreeMap<Long, Double> values = rollup(id, V2Window.Step.M1, ref, range.fromNs(), range.toNs());
        if (values.isEmpty()) {
            return Map.of();
        }
        double value = "sum".equals(column) ? values.values().stream().mapToDouble(Double::doubleValue).sum() : values.lastEntry().getValue();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put(metric + "." + column, number(value, ref.unit(), compact));
        return metrics;
    }

    private TreeMap<Long, Double> rollup(Identity id, V2Window.Step step, MetricRef ref, long fromNs, long toNs) throws SQLException {
        TreeMap<Long, Double> values = new TreeMap<>();
        String glob = glob(step.table(), id);
        if (glob == null) {
            return values;
        }
        String bucketExpr = "CAST(bucket_t AS BIGINT)";
        String bucket = step == V2Window.Step.M5 || step == V2Window.Step.H1
                ? "(" + bucketExpr + " // " + step.nanos + ") * " + step.nanos
                : bucketExpr;
        String agg = switch (ref.column()) {
            case "sum" -> "sum(sum)";
            case "n" -> "sum(n)";
            case "min" -> "min(min)";
            case "last" -> "arg_max(last, " + bucketExpr + ")";
            case "p50", "p90", "p99", "max" -> "max(" + ref.column() + ")";
            default -> "max(" + ref.column() + ")";
        };
        String sql = "SELECT " + bucket + ", " + agg + " FROM read_parquet('" + glob + "', hive_partitioning=false, union_by_name=true) "
                + "WHERE metric = '" + escape(ref.id()) + "' AND " + bucketExpr + " >= " + fromNs + " AND " + bucketExpr + " < " + toNs
                + " GROUP BY 1";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.put(result.getLong(1), result.getDouble(2));
            }
        }
        return values;
    }

    private double mean(Identity id, V2Window.Range range, MetricRef ref) throws SQLException {
        Map<Long, Double> values = rollup(id, V2Window.Step.M1, ref, range.fromNs(), range.toNs());
        if (values.isEmpty()) {
            return Double.NaN;
        }
        return values.values().stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private List<TopRow> ranks(Identity id, V2Window.Range range, String kind, String group) throws SQLException {
        String glob = glob("top_1m", id);
        if (glob == null) {
            return List.of();
        }
        boolean byStack = "stack".equals(group);
        String key = byStack ? "stack_id" : "\"key\"";
        String sql = "SELECT " + key + ", arg_max(stack_id, weight), sum(weight), sum(n) FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE kind = '" + escape(kind) + "' AND grp = '" + escape(byStack ? "method" : group)
                + "' AND t >= " + range.fromNs() + " AND t < " + range.toNs() + " GROUP BY 1";
        List<TopRow> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                rows.add(new TopRow(result.getString(1), result.getString(2), result.getDouble(3), result.getLong(4)));
            }
        }
        return rows;
    }

    private static Map<String, TopRow> index(List<TopRow> rows) {
        Map<String, TopRow> indexed = new LinkedHashMap<>();
        for (TopRow row : rows) {
            indexed.put(row.key(), row);
        }
        return indexed;
    }

    private static double baselineWeight(Map<String, TopRow> rows) {
        return rows.values().stream().mapToDouble(TopRow::weight).sum();
    }

    private static double ci(double share, long samples) {
        if (samples <= 0) {
            return 0.0;
        }
        return 1.96 * Math.sqrt(Math.max(0.0, share * (1.0 - share)) / samples);
    }

    private static double z(double share, long samples, double before, long beforeN) {
        double left = samples <= 0 ? 0.0 : share * (1.0 - share) / samples;
        double right = beforeN <= 0 ? 0.0 : before * (1.0 - before) / beforeN;
        double denom = Math.sqrt(left + right);
        if (denom == 0.0) {
            return 0.0;
        }
        return (share - before) / denom;
    }

    private Map<String, Object> tree(List<InstanceRow> rows, long nowNs) {
        Map<String, Map<String, Map<String, List<InstanceRow>>>> namespaces = new TreeMap<>();
        for (InstanceRow row : rows) {
            namespaces.computeIfAbsent(row.namespace(), ignored -> new TreeMap<>())
                    .computeIfAbsent(row.pod(), ignored -> new TreeMap<>())
                    .computeIfAbsent(row.container(), ignored -> new ArrayList<>())
                    .add(row);
        }
        List<Map<String, Object>> namespaceList = new ArrayList<>();
        for (var namespace : namespaces.entrySet()) {
            List<Map<String, Object>> pods = new ArrayList<>();
            for (var pod : namespace.getValue().entrySet()) {
                List<Map<String, Object>> containers = new ArrayList<>();
                String uid = null;
                for (var container : pod.getValue().entrySet()) {
                    List<InstanceRow> instances = container.getValue();
                    instances.sort(Comparator.comparingLong(InstanceRow::jvmStart).reversed());
                    if (uid == null) {
                        uid = instances.get(0).podUid();
                    }
                    List<Map<String, Object>> rendered = new ArrayList<>();
                    for (InstanceRow instance : instances) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("jvm_instance_id", instance.id());
                        item.put("seq", instance.seq());
                        if (instance.jvmStart() > 0) {
                            item.put("jvmStart", Instant.ofEpochMilli(instance.jvmStart()).toString());
                        }
                        if (instance.pid() > 0) {
                            item.put("pid", instance.pid());
                        }
                        put(item, "jdk", instance.jdk());
                        put(item, "restart_reason_hint", instance.hint());
                        item.put("from", Instant.ofEpochMilli(instance.fromNs() / 1_000_000L).toString());
                        item.put("to", Instant.ofEpochMilli(instance.toNs() / 1_000_000L).toString());
                        boolean live = nowNs - instance.toNs() <= LIVE_NS;
                        item.put("live", live);
                        item.put("retention_state", live ? "live" : "ended");
                        rendered.add(item);
                    }
                    containers.add(Map.of("name", container.getKey(), "instances", rendered));
                }
                Map<String, Object> podBody = new LinkedHashMap<>();
                podBody.put("name", pod.getKey());
                put(podBody, "uid", uid);
                podBody.put("containers", containers);
                pods.add(podBody);
            }
            namespaceList.add(Map.of("name", namespace.getKey(), "pods", pods));
        }
        return Map.of("namespaces", namespaceList);
    }

    private static List<Map<String, Object>> cluster(List<Map<String, Object>> rows) {
        Map<Long, Map<String, Object>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long t = ((Number) row.get("t")).longValue();
            long bucket = Math.floorDiv(t, 60_000_000_000L) * 60_000_000_000L;
            Map<String, Object> current = buckets.computeIfAbsent(bucket, ignored -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("t", bucket);
                created.put("count", 0);
                created.put("max", 0.0);
                created.put("kind", row.get("kind"));
                return created;
            });
            current.put("count", ((Integer) current.get("count")) + 1);
            Object magnitude = row.get("magnitude");
            if (magnitude instanceof Number number) {
                current.put("max", Math.max((Double) current.get("max"), number.doubleValue()));
            }
        }
        return new ArrayList<>(buckets.values());
    }

    private static String eventTable(String type) {
        return switch (type) {
            case "gc" -> "gc";
            case "gc_phase" -> "gc_phase";
            case "io", "lock", "safepoint", "vm_op", "compile", "deopt", "exception" -> "duration_event";
            default -> null;
        };
    }

    private static String eventFilter(String type) {
        return switch (type) {
            case "io" -> " AND type IN ('jdk.FileRead', 'jdk.FileWrite', 'jdk.FileForce', 'jdk.SocketRead', 'jdk.SocketWrite')";
            case "lock" -> " AND type IN ('jdk.JavaMonitorEnter', 'jdk.JavaMonitorWait', 'jdk.ThreadPark', 'profiler.NativeLock')";
            case "safepoint" -> " AND type = 'jdk.SafepointBegin'";
            case "vm_op" -> " AND type = 'jdk.ExecuteVMOperation'";
            case "compile" -> " AND type = 'jdk.Compilation'";
            case "deopt" -> " AND type = 'jdk.Deoptimization'";
            case "exception" -> " AND type IN ('jdk.JavaExceptionThrow', 'jdk.JavaErrorThrow')";
            case "gc_phase" -> " AND level = 0";
            default -> "";
        };
    }

    private static String eventLabel(String type) {
        return "gc".equals(type) || "gc_phase".equals(type) ? "name" : "type";
    }

    private static String eventTarget(String type) {
        if ("gc".equals(type) || "gc_phase".equals(type)) {
            return "NULL";
        }
        return "target";
    }

    private static int severity(String value) {
        return switch (value == null ? "" : value) {
            case "critical" -> 2;
            case "warn" -> 1;
            default -> 0;
        };
    }

    private static List<MetricRef> metrics(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("metrics is required");
        }
        List<MetricRef> refs = new ArrayList<>();
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String id = trimmed;
            String column = null;
            int dot = trimmed.lastIndexOf('.');
            if (dot > 0) {
                String suffix = trimmed.substring(dot + 1);
                if (List.of("p50", "p90", "p99", "sum", "min", "max", "last", "n").contains(suffix)) {
                    id = trimmed.substring(0, dot);
                    column = suffix;
                }
            }
            MetricRegistry.Metric metric = MetricRegistry.get(id);
            if (metric == null) {
                throw new IllegalArgumentException("unknown metric " + id);
            }
            if (column == null) {
                column = switch (metric.kind()) {
                    case "duration" -> "p99";
                    case "counter", "sample-share" -> "sum";
                    default -> "last";
                };
            }
            refs.add(new MetricRef(trimmed, id, column, metric.unit(), metric.nativePeriod()));
        }
        if (refs.isEmpty()) {
            throw new IllegalArgumentException("metrics is required");
        }
        if (refs.size() > 4) {
            throw new IllegalArgumentException("at most 4 metrics");
        }
        return refs;
    }

    private static MetricRef metric(String id, String column) {
        MetricRegistry.Metric metric = MetricRegistry.get(id);
        return new MetricRef(id + "." + column, id, column, metric.unit(), metric.nativePeriod());
    }

    private static String coarsestNative(List<MetricRef> refs) {
        String period = "1s";
        for (MetricRef ref : refs) {
            if ("1m".equals(ref.nativePeriod()) || "5m".equals(ref.nativePeriod()) || "1h".equals(ref.nativePeriod())) {
                period = ref.nativePeriod();
            }
        }
        return period;
    }

    private String glob(String table, Identity id) {
        return files(table, partition(id.ns(), id.pod(), id.container(), id.instance()));
    }

    private String files(String table, String suffix) {
        try {
            Path dir = suffix == null || suffix.isEmpty() ? root.resolve(table) : root.resolve(table).resolve(suffix);
            if (!Files.isDirectory(dir)) {
                return null;
            }
            try (var walk = Files.walk(dir)) {
                if (walk.noneMatch(path -> path.getFileName().toString().endsWith(".parquet"))) {
                    return null;
                }
            }
            return dir.toAbsolutePath().toString().replace("'", "''") + "/**/*.parquet";
        } catch (java.io.IOException error) {
            return null;
        }
    }

    private static String partition(String ns, String pod, String container, String instance) {
        String path = "namespace=" + ParquetTables.sanitize(ns) + "/pod=" + ParquetTables.sanitize(pod) + "/container="
                + ParquetTables.sanitize(container);
        if (instance != null) {
            path += "/jvm_instance_id=" + ParquetTables.sanitize(instance);
        }
        return path;
    }

    private static Map<String, Object> identity(Identity id) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("identity", Map.of(
                "ns", id.ns(), "pod", id.pod(), "container", id.container(), "jvm_instance_id", id.instance()));
        return response;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null && !(value instanceof String text && text.isBlank())) {
            map.put(key, value);
        }
    }

    private static Object number(double value, String unit, boolean compact) {
        if (!Double.isFinite(value)) {
            return null;
        }
        if ("bytes".equals(unit)) {
            return Math.round(value);
        }
        return CompactNumbers.round(value, compact);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private static String arg(String column) {
        return "arg_max(\"" + column + "\", t) FILTER (WHERE \"" + column + "\" IS NOT NULL)";
    }

    private Map<String, ReadSignal> signalState(Identity id) throws SQLException {
        Map<String, ReadSignal> signals = new LinkedHashMap<>();
        String glob = glob("signal", id);
        if (glob == null) {
            return signals;
        }
        String sql = "SELECT signal_id, status, reason, min_jdk, hint FROM ("
                + "SELECT signal_id, status, reason, min_jdk, hint, row_number() OVER (PARTITION BY signal_id ORDER BY t DESC) AS rn "
                + "FROM read_parquet('" + glob + "', hive_partitioning=false, union_by_name=true)"
                + ") WHERE rn = 1";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                Integer minJdk = result.getObject(4) == null ? null : result.getInt(4);
                signals.put(result.getString(1), new ReadSignal(result.getString(1), result.getString(2), result.getString(3), minJdk,
                        result.getString(5)));
            }
        }
        return signals;
    }

    private List<String> findings(Identity id) throws SQLException {
        String glob = glob("finding", id);
        if (glob == null) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        String sql = "SELECT DISTINCT code FROM read_parquet('" + glob + "', hive_partitioning=false, union_by_name=true)";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                codes.add(result.getString(1));
            }
        }
        return codes;
    }

    public record Identity(String ns, String pod, String container, String instance) {}

    private record MetricRef(String name, String id, String column, String unit, String nativePeriod) {}

    private record ReadSignal(String id, String status, String reason, Integer minJdk, String hint) {}

    private record TopRow(String key, String stackId, double weight, long n) {}

    private record InstanceRow(
            String namespace, String pod, String container, String id, String podUid, int seq, long jvmStart, long pid, String jdk,
            String hint, long fromNs, long toNs) {}
}

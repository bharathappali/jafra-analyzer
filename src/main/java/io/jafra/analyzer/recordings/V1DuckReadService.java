package io.jafra.analyzer.recordings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.jafra.analyzer.api.v2.V2Database;
import io.jafra.analyzer.ingest.decode.DecodePipeline;
import io.jafra.analyzer.ingest.decode.ParquetTables;
import io.jafra.analyzer.recordings.analytics.AnalyticsModels;
import io.jafra.analyzer.recordings.analytics.CorrelationsEngine;
import io.jafra.analyzer.recordings.stats.Percentiles;

/**
 * v1 analytics and aggregations read from the decoded Parquet tables. The JMC builders stay for the
 * other v1 endpoints.
 */
@ApplicationScoped
public class V1DuckReadService {
    private static final int DEFAULT_TOP = 20;
    private static final int MAX_TOP = 100;
    private static final int DEFAULT_ANALYTICS_TOP = 15;
    private static final int MAX_ANALYTICS_TOP = 30;
    private static final long DEFAULT_CONTENTION_MIN_NS = 20_000_000L;
    private static final Set<String> KINDS = Set.of(
            "gc-pauses", "trends", "allocations", "samples", "contention", "exceptions");
    private static final List<String> SUBSYSTEMS = List.of(
            "context", "cpu", "wall_time", "threads", "heap", "allocations", "gc", "locks", "jit", "io",
            "container", "native_memory", "exceptions", "vm_internal");

    private final V2Database database;
    private final DecodePipeline pipeline;
    private final CorrelationsEngine correlations;

    @Inject
    public V1DuckReadService(V2Database database, DecodePipeline pipeline, CorrelationsEngine correlations) {
        this.database = database;
        this.pipeline = pipeline;
        this.correlations = correlations;
    }

    public JfrAggregationService.AggregationDocument aggregate(
            RecordingCatalog.WindowSelection selection,
            String kind,
            Integer limit,
            String minDuration,
            String flavor,
            boolean stackIds) throws SQLException {
        String normalized = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        if (!KINDS.contains(normalized)) {
            throw new IllegalArgumentException(
                    "kind must be one of: gc-pauses, trends, allocations, samples, contention, exceptions");
        }
        int top = clamp(limit, DEFAULT_TOP, MAX_TOP, "limit");
        pipeline.ensureDecoded();
        Scope scope = scope(selection);
        return switch (normalized) {
            case "gc-pauses" -> gcPauses(selection, scope);
            case "trends" -> trends(selection, scope);
            case "allocations" -> allocations(selection, scope, top, stackIds);
            case "samples" -> samples(selection, scope, top, flavor, stackIds);
            case "contention" -> contention(selection, scope, top, minDuration, stackIds);
            case "exceptions" -> exceptions(selection, scope, top, stackIds);
            default -> throw new IllegalArgumentException("unsupported kind: " + kind);
        };
    }

    public AnalyticsModels.AnalyticsDocument timeseries(
            RecordingCatalog.WindowSelection selection, Integer top, Long bucketSeconds, String subsystemFilter, boolean stackIds)
            throws SQLException {
        return analytics(selection, true, top, bucketSeconds, subsystemFilter, stackIds);
    }

    public AnalyticsModels.AnalyticsDocument dailyStats(
            RecordingCatalog.WindowSelection selection, Integer top, String subsystemFilter, boolean stackIds)
            throws SQLException {
        return analytics(selection, false, top, null, subsystemFilter, stackIds);
    }

    public ResolvedStack stack(String stackId, int depth) throws SQLException {
        if (depth < 1) {
            throw new IllegalArgumentException("depth must be at least 1");
        }
        pipeline.ensureDecoded();
        String glob = tree("stack");
        if (glob == null || stackId == null || stackId.isBlank()) {
            return null;
        }
        String frames;
        int depthTotal;
        String sql = "SELECT frames, depth FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE stack_id = '" + escape(stackId) + "' LIMIT 1";
        try (Statement statement = database.connection().createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                return null;
            }
            frames = result.getString(1);
            depthTotal = result.getInt(2);
        }
        List<ResolvedFrame> loaded = framesOf(frames, depth);
        String leaf = loaded.isEmpty() ? null : loaded.get(0).method();
        String topApp = leaf;
        for (ResolvedFrame frame : loaded) {
            if (!StackIds.platformClass(frame.className())) {
                topApp = frame.method();
                break;
            }
        }
        return new ResolvedStack(stackId, depthTotal, leaf, topApp, loaded);
    }

    private AnalyticsModels.AnalyticsDocument analytics(
            RecordingCatalog.WindowSelection selection,
            boolean timeseriesMode,
            Integer top,
            Long bucketSeconds,
            String subsystemFilter,
            boolean stackIds) throws SQLException {
        int capped = clamp(top, DEFAULT_ANALYTICS_TOP, MAX_ANALYTICS_TOP, "top");
        List<String> filter = parseFilter(subsystemFilter);
        pipeline.ensureDecoded();
        Scope scope = scope(selection);
        long cpuSamples = count(scope, "sample", "kind = 'cpu'");
        long wallSamples = count(scope, "sample", "kind = 'wall'");
        long allocSamples = count(scope, "sample", "kind = 'alloc'");
        long pauses = count(scope, "gc_phase", "level = 0");
        TimeBucketGrid grid = timeseriesMode ? TimeBucketGrid.of(selection.from(), selection.to(), bucketSeconds) : null;
        Map<String, List<RollupPoint>> rollups = rollupPoints(scope);
        Map<String, AnalyticsModels.SubsystemResult> subsystems = new LinkedHashMap<>();
        for (String id : SUBSYSTEMS) {
            if (!filter.isEmpty() && !filter.contains(id)) {
                continue;
            }
            subsystems.put(id, subsystem(id, scope, grid, capped, stackIds, cpuSamples, wallSamples, allocSamples, pauses, rollups));
        }
        Set<String> seen = new java.util.LinkedHashSet<>();
        if (cpuSamples > 0) {
            seen.add("jdk.ExecutionSample");
        }
        if (wallSamples > 0) {
            seen.add("profiler.WallClockSample");
        }
        if (pauses > 0) {
            seen.add("jdk.GCPhasePause");
        }
        if (!points(rollups, "heap.used").isEmpty()) {
            seen.add("jdk.GCHeapSummary");
        }
        if (!points(rollups, "cpu.jvm_user").isEmpty()) {
            seen.add("jdk.CPULoad");
        }
        return new AnalyticsModels.AnalyticsDocument(
                AnalyticsModels.SCHEMA_VERSION,
                timeseriesMode ? AnalyticsModels.MODE_TIMESERIES : AnalyticsModels.MODE_DAILY,
                AnalysisEnvelope.from(selection),
                AnalyticsCoverage.compute(selection, seen, wallSamples > 0),
                subsystems,
                new LinkedHashMap<>(correlations.correlate(subsystems)));
    }

    private AnalyticsModels.SubsystemResult subsystem(
            String id,
            Scope scope,
            TimeBucketGrid grid,
            int top,
            boolean stackIds,
            long cpuSamples,
            long wallSamples,
            long allocSamples,
            long pauses,
            Map<String, List<RollupPoint>> rollups) throws SQLException {
        Map<String, Object> rollup = new LinkedHashMap<>();
        Map<String, Object> evidence = new LinkedHashMap<>();
        List<AnalyticsModels.MetricSeries> series = new ArrayList<>();
        boolean present = true;
        List<String> expected = List.of();
        switch (id) {
            case "cpu" -> {
                expected = List.of("jdk.ExecutionSample", "jdk.CPULoad");
                present = cpuSamples > 0 || !points(rollups, "cpu.jvm_user").isEmpty();
                rollup.put("cpuSampleCount", cpuSamples);
                evidence.put("topStacks", topStacks(scope, "kind = 'cpu'", cpuSamples, top, stackIds));
                addGaugeSeries(series, grid, rollups, "cpu.jvm_user", "jvmCpuPercent", "JVM CPU", "percent");
                addGaugeSeries(series, grid, rollups, "cpu.machine_total", "machineTotalPercent", "Machine CPU", "percent");
                addCountSeries(series, grid, rollups, "sample.cpu", "cpuSampleRatePerSec", "CPU samples", "count");
            }
            case "wall_time" -> {
                expected = List.of("profiler.WallClockSample");
                present = wallSamples > 0;
                rollup.put("wallSampleCount", wallSamples);
                evidence.put("topStacks", topStacks(scope, "kind = 'wall'", wallSamples, top, stackIds));
                addCountSeries(series, grid, rollups, "sample.wall", "wallSamplesPerSec", "Wall samples", "count");
                addCountSeries(series, grid, rollups, "sample.wall", "percentSleeping", "Sleeping share", "percent");
                addCountSeries(series, grid, rollups, "sample.wall", "runnableSamplesPerBucket", "Runnable samples", "count");
            }
            case "heap" -> {
                expected = List.of("jdk.GCHeapSummary");
                List<RollupPoint> heap = points(rollups, "heap.used");
                present = !heap.isEmpty();
                rollup.put("heapUsedSlopePerHour", slope(heap));
                addGaugeSeries(series, grid, rollups, "heap.used", "heapUsed", "Heap used", "bytes");
            }
            case "allocations" -> {
                expected = List.of("jdk.ObjectAllocationSample");
                present = allocSamples > 0;
                rollup.put("eventCount", allocSamples);
                evidence.put("top", classRanks(scope, "kind = 'alloc'", "object_class", top));
                addSumSeries(series, grid, rollups, "alloc.bytes", "allocationBytesPerBucket", "Allocated bytes", "bytes");
            }
            case "gc" -> {
                expected = List.of("jdk.GCPhasePause");
                present = pauses > 0;
                rollup.put("pauseCount", pauses);
                addSumSeries(series, grid, rollups, "gc.pause_ms", "pauseSumMsPerBucket", "Pause time", "ms");
            }
            case "container" -> {
                expected = List.of("jdk.ContainerCPUThrottling");
                present = !points(rollups, "container.throttled_slices").isEmpty()
                        || !points(rollups, "container.elapsed_slices").isEmpty();
                addGaugeSeries(series, grid, rollups, "container.throttled_slices", "throttleRatio", "Throttle ratio", "ratio");
            }
            case "locks" -> {
                expected = List.of("jdk.JavaMonitorEnter");
                long locks = count(scope, "duration_event", "type IN ('jdk.JavaMonitorEnter','jdk.ThreadPark','profiler.NativeLock')");
                present = locks > 0;
                rollup.put("eventCount", locks);
                addSumSeries(series, grid, rollups, "lock.enter_ms", "blockedMsPerBucket", "Blocked time", "ms");
            }
            case "exceptions" -> {
                expected = List.of("jdk.JavaExceptionThrow");
                long thrown = count(scope, "duration_event", "type = 'jdk.JavaExceptionThrow'");
                present = thrown > 0 || !points(rollups, "exceptions.throwables").isEmpty();
                rollup.put("eventCount", thrown);
                addSumSeries(series, grid, rollups, "exceptions.throwables", "throwsPerBucket", "Throws", "count");
            }
            case "io" -> {
                expected = List.of("jdk.FileRead", "jdk.SocketRead");
                long io = count(scope, "duration_event", "type IN ('jdk.FileRead','jdk.FileWrite','jdk.SocketRead','jdk.SocketWrite')");
                present = io > 0;
                rollup.put("eventCount", io);
                addSumSeries(series, grid, rollups, "io.socket_read_ms", "ioDurationMsPerBucket", "I/O time", "ms");
            }
            case "threads" -> {
                expected = List.of("jdk.JavaThreadStatistics");
                present = !points(rollups, "threads.live").isEmpty();
                addGaugeSeries(series, grid, rollups, "threads.live", "liveThreads", "Live threads", "count");
            }
            case "jit" -> {
                expected = List.of("jdk.Compilation");
                long compiles = count(scope, "duration_event", "type = 'jdk.Compilation'");
                present = compiles > 0;
                rollup.put("compilationCount", compiles);
            }
            case "native_memory" -> {
                expected = List.of("profiler.Malloc");
                long mallocs = count(scope, "sample", "kind = 'native_malloc'");
                present = mallocs > 0;
                rollup.put("mallocBytesTotal", sum(scope, "sample", "kind = 'native_malloc'", "size"));
            }
            case "vm_internal" -> {
                expected = List.of("jdk.SafepointBegin");
                long safepoints = count(scope, "duration_event", "type = 'jdk.SafepointBegin'");
                present = safepoints > 0;
                rollup.put("safepointCount", safepoints);
            }
            default -> rollup.put("chunkCount", scope.selection.chunks());
        }
        AnalyticsModels.Availability availability = present
                ? new AnalyticsModels.Availability(AnalyticsModels.AVAIL_PRESENT, expected, expected)
                : new AnalyticsModels.Availability(
                        AnalyticsModels.AVAIL_ABSENT, expected, List.of(), "no_data_in_window", null, null);
        List<AnalyticsModels.MetricSeries> published = grid == null || series.isEmpty() ? null : series;
        return new AnalyticsModels.SubsystemResult(id, availability, rollup, published, evidence.isEmpty() ? null : evidence, List.of());
    }

    private JfrAggregationService.AggregationDocument gcPauses(RecordingCatalog.WindowSelection selection, Scope scope)
            throws SQLException {
        List<Double> pauseMs = new ArrayList<>();
        String glob = scope.glob("gc_phase");
        if (glob != null) {
            String sql = "SELECT dur_ms FROM read_parquet('" + glob
                    + "', hive_partitioning=false, union_by_name=true) WHERE level = 0 AND " + scope.time("t");
            try (Statement statement = database.connection().createStatement(); ResultSet result = statement.executeQuery(sql)) {
                while (result.next()) {
                    pauseMs.add(result.getDouble(1));
                }
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        if (pauseMs.isEmpty()) {
            stats.put("count", 0L);
            stats.put("sumMs", 0.0);
            stats.put("maxMs", 0.0);
        } else {
            Percentiles.Summary summary = Percentiles.summarize(pauseMs).orElseThrow();
            double sum = 0.0;
            for (double value : pauseMs) {
                sum += value;
            }
            stats.put("count", (long) pauseMs.size());
            stats.put("sumMs", sum);
            stats.put("maxMs", summary.max());
            stats.put("p50Ms", summary.p50());
            stats.put("p90Ms", summary.p90());
            stats.put("p95Ms", summary.p95());
            stats.put("p99Ms", summary.p99());
        }
        stats.put("youngGcCount", count(scope, "gc", "kind = 'young'"));
        stats.put("phaseBreakdown", phaseBreakdown(scope));
        return new JfrAggregationService.AggregationDocument(AnalysisEnvelope.from(selection), "gc-pauses", stats, List.of(), List.of());
    }

    private JfrAggregationService.AggregationDocument trends(RecordingCatalog.WindowSelection selection, Scope scope)
            throws SQLException {
        List<JfrAggregationService.TrendSeries> series = new ArrayList<>();
        List<JfrAggregationService.TrendPoint> points = gaugePoints(scope, "heap.used");
        if (!points.isEmpty()) {
            series.add(new JfrAggregationService.TrendSeries("jdk.GCHeapSummary", "heapUsed", points, slopePoints(points)));
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        for (JfrAggregationService.TrendSeries trend : series) {
            stats.put(trend.name() + "SlopePerHour", trend.slopePerHour());
        }
        return new JfrAggregationService.AggregationDocument(AnalysisEnvelope.from(selection), "trends", stats, series, List.of());
    }

    private JfrAggregationService.AggregationDocument allocations(
            RecordingCatalog.WindowSelection selection, Scope scope, int top, boolean stackIds) throws SQLException {
        List<JfrAggregationService.RankedEntry> ranked = new ArrayList<>();
        ranked.addAll(classEntries(scope, "kind = 'alloc'", "object_class", top));
        ranked.addAll(stackEntries(scope, "kind = 'alloc'", top, stackIds, "size"));
        Map<String, Object> stats = Map.of("eventCount", count(scope, "sample", "kind = 'alloc'"));
        return new JfrAggregationService.AggregationDocument(
                AnalysisEnvelope.from(selection), "allocations", stats, List.of(), ranked);
    }

    private JfrAggregationService.AggregationDocument samples(
            RecordingCatalog.WindowSelection selection, Scope scope, int top, String flavor, boolean stackIds)
            throws SQLException {
        String mode = flavor == null || flavor.isBlank() ? "all" : flavor.trim().toLowerCase(Locale.ROOT);
        String where = switch (mode) {
            case "cpu" -> "kind = 'cpu'";
            case "wall" -> "kind = 'wall'";
            default -> "kind IN ('cpu','wall')";
        };
        long total = count(scope, "sample", where);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("sampleCount", total);
        stats.put("flavor", mode);
        return new JfrAggregationService.AggregationDocument(
                AnalysisEnvelope.from(selection), "samples", stats, List.of(), stackEntries(scope, where, top, stackIds, "weight"));
    }

    private JfrAggregationService.AggregationDocument contention(
            RecordingCatalog.WindowSelection selection, Scope scope, int top, String minDuration, boolean stackIds)
            throws SQLException {
        Long minNs = JfrDuration.parseNanos(minDuration);
        if (minNs == null) {
            minNs = DEFAULT_CONTENTION_MIN_NS;
        }
        double minMs = minNs / 1_000_000.0;
        String where = "type IN ('jdk.JavaMonitorEnter','jdk.JavaMonitorWait','jdk.ThreadPark','profiler.NativeLock') AND dur_ms >= "
                + minMs;
        List<JfrAggregationService.RankedEntry> ranked = durationRanks(scope, where, "monitor_class", top, stackIds);
        Map<String, Object> stats = Map.of("eventCount", count(scope, "duration_event", where), "minDurationNs", minNs);
        return new JfrAggregationService.AggregationDocument(
                AnalysisEnvelope.from(selection), "contention", stats, List.of(), ranked);
    }

    private JfrAggregationService.AggregationDocument exceptions(
            RecordingCatalog.WindowSelection selection, Scope scope, int top, boolean stackIds) throws SQLException {
        String where = "type = 'jdk.JavaExceptionThrow'";
        List<JfrAggregationService.RankedEntry> ranked = new ArrayList<>();
        ranked.addAll(durationClassRanks(scope, where, top));
        ranked.addAll(durationStackRanks(scope, where, top, stackIds));
        Map<String, Object> stats = Map.of("eventCount", count(scope, "duration_event", where));
        return new JfrAggregationService.AggregationDocument(
                AnalysisEnvelope.from(selection), "exceptions", stats, List.of(), ranked);
    }

    private List<Map<String, Object>> topStacks(Scope scope, String where, long total, int top, boolean stackIds)
            throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JfrAggregationService.RankedEntry entry : stackEntries(scope, where, top, stackIds, "weight")) {
            Map<String, Object> row = new LinkedHashMap<>();
            if (!stackIds && entry.stack() != null) {
                row.put("stack", entry.stack());
            }
            row.put("sampleCount", entry.count());
            if (total > 0) {
                row.put("percentOfCpuSamples", 100.0 * entry.count() / total);
            }
            if (entry.matchKey() != null) {
                row.put("matchKey", entry.matchKey());
            }
            if (stackIds && entry.stackId() != null) {
                row.put("stackId", entry.stackId());
                row.put("leaf", entry.leaf());
                row.put("topAppFrame", entry.topAppFrame());
                row.put("depth", entry.depth());
            }
            rows.add(row);
        }
        return rows;
    }

    private List<JfrAggregationService.RankedEntry> stackEntries(
            Scope scope, String where, int top, boolean stackIds, String weightColumn) throws SQLException {
        String glob = scope.glob("sample");
        if (glob == null) {
            return List.of();
        }
        String sql = "SELECT stack_id, count(*), coalesce(sum(" + weightColumn + "), 0) FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE stack_id IS NOT NULL AND " + where + " AND "
                + scope.time("t") + " GROUP BY stack_id ORDER BY 2 DESC LIMIT " + top;
        List<JfrAggregationService.RankedEntry> entries = new ArrayList<>();
        try (Statement statement = database.connection().createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                String stackId = result.getString(1);
                StackText text = stackText(scope, stackId);
                long count = result.getLong(2);
                long bytes = Math.round(result.getDouble(3));
                entries.add(entry("stack", text, count, bytes, 0L, stackIds));
            }
        }
        return entries;
    }

    private List<Map<String, Object>> classRanks(Scope scope, String where, String column, int top) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JfrAggregationService.RankedEntry entry : classEntries(scope, where, column, top)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("group", entry.group());
            row.put("key", entry.key());
            row.put("count", entry.count());
            row.put("bytes", entry.bytes());
            rows.add(row);
        }
        return rows;
    }

    private List<JfrAggregationService.RankedEntry> classEntries(Scope scope, String where, String column, int top)
            throws SQLException {
        String glob = scope.glob("sample");
        if (glob == null) {
            return List.of();
        }
        String sql = "SELECT coalesce(" + column + ", '(unknown)'), count(*), coalesce(sum(size), 0) FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE " + where + " AND " + scope.time("t")
                + " GROUP BY 1 ORDER BY 3 DESC LIMIT " + top;
        List<JfrAggregationService.RankedEntry> entries = new ArrayList<>();
        try (Statement statement = database.connection().createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                entries.add(new JfrAggregationService.RankedEntry(
                        "class", result.getString(1), null, result.getLong(2), Math.round(result.getDouble(3)), 0L, null, null, null,
                        null, null));
            }
        }
        return entries;
    }

    private List<JfrAggregationService.RankedEntry> durationRanks(
            Scope scope, String where, String column, int top, boolean stackIds) throws SQLException {
        String glob = scope.glob("duration_event");
        if (glob == null) {
            return List.of();
        }
        String sql = "SELECT coalesce(" + column + ", type), count(*), coalesce(sum(dur_ms), 0) FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE " + where + " AND " + scope.time("t")
                + " GROUP BY 1 ORDER BY 3 DESC LIMIT " + top;
        List<JfrAggregationService.RankedEntry> entries = new ArrayList<>();
        try (Statement statement = database.connection().createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                long totalNs = Math.round(result.getDouble(3) * 1_000_000.0);
                entries.add(new JfrAggregationService.RankedEntry(
                        "monitor", result.getString(1), null, result.getLong(2), 0L, totalNs, null, null, null, null, null));
            }
        }
        if (stackIds) {
            entries.addAll(durationStackRanks(scope, where, top, true));
        }
        return entries;
    }

    private List<JfrAggregationService.RankedEntry> durationClassRanks(Scope scope, String where, int top) throws SQLException {
        String glob = scope.glob("duration_event");
        if (glob == null) {
            return List.of();
        }
        String sql = "SELECT coalesce(target, '(unknown)'), count(*) FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE " + where + " AND " + scope.time("t")
                + " GROUP BY 1 ORDER BY 2 DESC LIMIT " + top;
        List<JfrAggregationService.RankedEntry> entries = new ArrayList<>();
        try (Statement statement = database.connection().createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                entries.add(new JfrAggregationService.RankedEntry(
                        "class", result.getString(1), null, result.getLong(2), 0L, 0L, null, null, null, null, null));
            }
        }
        return entries;
    }

    private List<JfrAggregationService.RankedEntry> durationStackRanks(Scope scope, String where, int top, boolean stackIds)
            throws SQLException {
        String glob = scope.glob("duration_event");
        if (glob == null) {
            return List.of();
        }
        String sql = "SELECT stack_id, count(*), coalesce(sum(dur_ms), 0) FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE stack_id IS NOT NULL AND " + where + " AND "
                + scope.time("t") + " GROUP BY stack_id ORDER BY 2 DESC LIMIT " + top;
        List<JfrAggregationService.RankedEntry> entries = new ArrayList<>();
        try (Statement statement = database.connection().createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                StackText text = stackText(scope, result.getString(1));
                long totalNs = Math.round(result.getDouble(3) * 1_000_000.0);
                entries.add(entry("stack", text, result.getLong(2), 0L, totalNs, stackIds));
            }
        }
        return entries;
    }

    private static JfrAggregationService.RankedEntry entry(
            String group, StackText text, long count, long bytes, long totalNs, boolean stackIds) {
        String shown = text.text == null ? text.id : text.text;
        return new JfrAggregationService.RankedEntry(
                group,
                stackIds ? text.id : shown,
                stackIds ? null : text.text,
                count,
                bytes,
                totalNs,
                stackIds ? text.id : null,
                stackIds ? text.leaf : null,
                stackIds ? text.topApp : null,
                stackIds ? text.depth : null,
                text.id);
    }

    private StackText stackText(Scope scope, String stackId) throws SQLException {
        String glob = scope.glob("stack");
        if (glob == null || stackId == null) {
            return new StackText(stackId, stackId, null, null, 0);
        }
        String sql = "SELECT frames, depth FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE stack_id = '" + escape(stackId) + "' LIMIT 1";
        String joined = null;
        int depth = 0;
        try (Statement statement = database.connection().createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (result.next()) {
                joined = result.getString(1);
                depth = result.getInt(2);
            }
        }
        List<ResolvedFrame> frames = framesOf(joined, 64);
        if (frames.isEmpty()) {
            return new StackText(stackId, stackId, null, null, depth);
        }
        StringBuilder text = new StringBuilder();
        String leaf = frames.get(0).method();
        String topApp = leaf;
        for (ResolvedFrame frame : frames) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(frame.className() == null || frame.className().isBlank() ? frame.method() : frame.className() + "." + frame.method());
            if (topApp.equals(leaf) && !StackIds.platformClass(frame.className())) {
                topApp = frame.method();
            }
        }
        return new StackText(stackId, text.toString(), leaf, topApp, depth == 0 ? frames.size() : depth);
    }

    private List<ResolvedFrame> framesOf(String joined, int limit) throws SQLException {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        List<String> ids = List.of(joined.split(","));
        int count = Math.min(limit, ids.size());
        String frames = tree("frame");
        if (frames == null || count == 0) {
            return List.of();
        }
        String in = ids.subList(0, count).stream().map(value -> "'" + escape(value) + "'").reduce((a, b) -> a + "," + b).orElse("''");
        Map<String, ResolvedFrame> byId = new LinkedHashMap<>();
        String sql = "SELECT frame_id, \"class\", method, line, type FROM read_parquet('" + frames
                + "', hive_partitioning=false, union_by_name=true) WHERE frame_id IN (" + in + ")";
        try (Statement statement = database.connection().createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                byId.put(result.getString(1), new ResolvedFrame(
                        result.getString(3) == null ? "" : result.getString(3),
                        result.getString(2),
                        result.getInt(4),
                        result.getString(5) == null ? "" : result.getString(5)));
            }
        }
        List<ResolvedFrame> ordered = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ResolvedFrame frame = byId.get(ids.get(i));
            if (frame != null) {
                ordered.add(frame);
            }
        }
        return ordered;
    }

    private List<Map<String, Object>> phaseBreakdown(Scope scope) throws SQLException {
        String glob = scope.glob("gc_phase");
        if (glob == null) {
            return List.of();
        }
        String sql = "SELECT coalesce(name, '(unknown)'), count(*), max(dur_ms) FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE level > 0 AND " + scope.time("t") + " GROUP BY 1 ORDER BY 2 DESC";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement statement = database.connection().createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", result.getString(1));
                row.put("count", result.getLong(2));
                row.put("maxMs", result.getDouble(3));
                rows.add(row);
            }
        }
        return rows;
    }

    private List<JfrAggregationService.TrendPoint> gaugePoints(Scope scope, String metric) throws SQLException {
        String glob = scope.glob("gauge");
        if (glob == null) {
            return List.of();
        }
        String sql = "SELECT t, value FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE metric = '" + escape(metric) + "' AND " + scope.time("t")
                + " ORDER BY t";
        List<JfrAggregationService.TrendPoint> points = new ArrayList<>();
        try (Statement statement = database.connection().createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                points.add(new JfrAggregationService.TrendPoint(result.getLong(1), Math.round(result.getDouble(2))));
            }
        }
        return points;
    }

    private Map<String, List<RollupPoint>> rollupPoints(Scope scope) throws SQLException {
        Map<String, List<RollupPoint>> byMetric = new LinkedHashMap<>();
        String glob = scope.glob("rollup_1m");
        if (glob == null) {
            return byMetric;
        }
        String sql = "SELECT metric, CAST(bucket_t AS BIGINT), sum(n), sum(\"sum\"), max(last) FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE " + scope.time("bucket_t") + " GROUP BY 1, 2 ORDER BY 2";
        try (Statement statement = database.connection().createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                byMetric.computeIfAbsent(result.getString(1), ignored -> new ArrayList<>())
                        .add(new RollupPoint(result.getLong(2), result.getLong(3), result.getDouble(4), result.getDouble(5)));
            }
        }
        return byMetric;
    }

    private static List<RollupPoint> points(Map<String, List<RollupPoint>> rollups, String metric) {
        return rollups.getOrDefault(metric, List.of());
    }

    private static void addGaugeSeries(
            List<AnalyticsModels.MetricSeries> series,
            TimeBucketGrid grid,
            Map<String, List<RollupPoint>> rollups,
            String metric,
            String id,
            String label,
            String unit) {
        addSeries(series, grid, points(rollups, metric), id, label, unit, false);
    }

    private static void addSumSeries(
            List<AnalyticsModels.MetricSeries> series,
            TimeBucketGrid grid,
            Map<String, List<RollupPoint>> rollups,
            String metric,
            String id,
            String label,
            String unit) {
        addSeries(series, grid, points(rollups, metric), id, label, unit, true);
    }

    private static void addCountSeries(
            List<AnalyticsModels.MetricSeries> series,
            TimeBucketGrid grid,
            Map<String, List<RollupPoint>> rollups,
            String metric,
            String id,
            String label,
            String unit) {
        addSeries(series, grid, points(rollups, metric), id, label, unit, false);
    }

    private static void addSeries(
            List<AnalyticsModels.MetricSeries> series,
            TimeBucketGrid grid,
            List<RollupPoint> points,
            String id,
            String label,
            String unit,
            boolean useSum) {
        if (grid == null || points.isEmpty()) {
            return;
        }
        double[] value = new double[grid.bucketCount()];
        boolean[] seen = new boolean[grid.bucketCount()];
        for (RollupPoint point : points) {
            int index = grid.indexFor(point.bucket);
            if (index < 0) {
                continue;
            }
            value[index] += useSum ? point.sum : point.last;
            seen[index] = true;
        }
        List<AnalyticsModels.SeriesPoint> out = new ArrayList<>();
        for (TimeBucketGrid.BucketPoint bucket : grid.isoStarts()) {
            Number number = seen[bucket.index()] ? value[bucket.index()] : null;
            out.add(new AnalyticsModels.SeriesPoint(bucket.at(), number, number, number, number));
        }
        series.add(new AnalyticsModels.MetricSeries(id, label, unit, null, out));
    }

    private long count(Scope scope, String table, String where) throws SQLException {
        String glob = scope.glob(table);
        if (glob == null) {
            return 0L;
        }
        String sql = "SELECT count(*) FROM read_parquet('" + glob + "', hive_partitioning=false, union_by_name=true) WHERE " + where
                + " AND " + scope.time("t");
        try (Statement statement = database.connection().createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }

    private double sum(Scope scope, String table, String where, String column) throws SQLException {
        String glob = scope.glob(table);
        if (glob == null) {
            return 0.0;
        }
        String sql = "SELECT coalesce(sum(" + column + "), 0) FROM read_parquet('" + glob
                + "', hive_partitioning=false, union_by_name=true) WHERE " + where + " AND " + scope.time("t");
        try (Statement statement = database.connection().createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getDouble(1) : 0.0;
        }
    }

    private static double slope(List<RollupPoint> points) {
        List<JfrAggregationService.TrendPoint> trend = new ArrayList<>();
        for (RollupPoint point : points) {
            trend.add(new JfrAggregationService.TrendPoint(point.bucket, Math.round(point.last)));
        }
        return slopePoints(trend);
    }

    private static double slopePoints(List<JfrAggregationService.TrendPoint> points) {
        if (points.size() < 2) {
            return 0.0;
        }
        double sumX = 0.0;
        double sumY = 0.0;
        double sumXY = 0.0;
        double sumXX = 0.0;
        int n = points.size();
        for (JfrAggregationService.TrendPoint point : points) {
            double x = point.timeNs() / 1_000_000_000.0;
            double y = point.value();
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumXX += x * x;
        }
        double denom = n * sumXX - sumX * sumX;
        if (denom == 0.0) {
            return 0.0;
        }
        return ((n * sumXY - sumX * sumY) / denom) * 3600.0;
    }

    private static int clamp(Integer requested, int fallback, int max, String name) {
        if (requested == null) {
            return fallback;
        }
        if (requested <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        if (requested > max) {
            throw new IllegalArgumentException(name + " exceeds maximum of " + max);
        }
        return requested;
    }

    private static List<String> parseFilter(String subsystemFilter) {
        if (subsystemFilter == null || subsystemFilter.isBlank()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String piece : subsystemFilter.split(",")) {
            if (!piece.isBlank()) {
                ids.add(piece.trim().toLowerCase(Locale.ROOT));
            }
        }
        return ids;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private Scope scope(RecordingCatalog.WindowSelection selection) {
        long fromNs = selection.from().getEpochSecond() * 1_000_000_000L + selection.from().getNano();
        long toNs = selection.to().getEpochSecond() * 1_000_000_000L + selection.to().getNano();
        return new Scope(database.root(), selection, fromNs, toNs);
    }

    private String tree(String table) {
        Path dir = database.root().resolve(table);
        return parquetGlob(dir);
    }

    private static String parquetGlob(Path dir) {
        try {
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

    public record ResolvedStack(String stackId, int depth, String leaf, String topAppFrame, List<ResolvedFrame> frames) {}

    public record ResolvedFrame(String method, String className, int line, String type) {}

    private record StackText(String id, String text, String leaf, String topApp, int depth) {}

    private record RollupPoint(long bucket, long n, double sum, double last) {}

    private record Scope(Path root, RecordingCatalog.WindowSelection selection, long fromNs, long toNs) {
        String time(String column) {
            return column + " >= " + fromNs + " AND " + column + " < " + toNs;
        }

        String glob(String table) {
            Path dir = root.resolve(table)
                    .resolve("namespace=" + ParquetTables.sanitize(selection.key().namespace()))
                    .resolve("pod=" + ParquetTables.sanitize(selection.key().pod()))
                    .resolve("container=" + ParquetTables.sanitize(selection.key().container()));
            return parquetGlob(dir);
        }
    }
}

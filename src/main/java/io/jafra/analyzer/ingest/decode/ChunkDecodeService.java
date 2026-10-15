package io.jafra.analyzer.ingest.decode;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

import io.jafra.analyzer.identity.JvmInstanceIds;
import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;

/** Decodes one committed chunk into the PR3 Parquet tables. A chunk already marked ok is a no-op. */
public final class ChunkDecodeService {
    private static final Logger LOG = Logger.getLogger(ChunkDecodeService.class);
    private static final Pattern FEATURE = Pattern.compile("(\\d+)");
    private static final List<String> IDENTITY = List.of("namespace", "pod", "container", "jvm_instance_id", "t");

    private final ChunkStore store;
    private final IngestCatalog catalog;
    private final Path v2Root;

    public ChunkDecodeService(ChunkStore store, IngestCatalog catalog, Path v2Root) {
        this.store = store;
        this.catalog = catalog;
        this.v2Root = v2Root;
    }

    public void backfill() {
        List<ChunkMetadata> pending = new ArrayList<>();
        for (ChunkStore.StoredChunk stored : store.storedChunks()) {
            ChunkMetadata metadata = store.readMeta(stored);
            if (metadata != null) {
                pending.add(metadata);
            }
        }
        pending.sort(Comparator.comparingLong(ChunkMetadata::chunkStartTimeNs).thenComparingLong(ChunkMetadata::chunkOffset));
        for (ChunkMetadata metadata : pending) {
            decode(metadata);
        }
    }

    public void decode(ChunkMetadata metadata) {
        if (metadata == null) {
            return;
        }
        synchronized (catalog) {
            decodeLocked(metadata);
        }
    }

    private void decodeLocked(ChunkMetadata metadata) {
        try {
            if (catalog.isIngested(metadata.chunkId())) {
                return;
            }
            Path payload = store.payloadPath(metadata.chunkId());
            JfrChunkReader.ReadChunk chunk = JfrChunkReader.read(payload);
            long tStart = metadata.chunkStartTimeNs() > 0 ? metadata.chunkStartTimeNs() : 0L;
            long tEnd = tStart + Math.max(0L, metadata.chunkDurationNs());
            Resolved resolved = resolve(metadata, chunk, tStart);
            long rows = write(metadata, chunk, resolved, tStart);
            RollupWriter.refresh(catalog.connection(), v2Root, metadata.namespace(), metadata.podName(), metadata.containerName(),
                    resolved.id, tStart, tEnd);
            String hint = hint(chunk);
            catalog.touchInstance(resolved.id, tEnd, hint);
            catalog.record(metadata.chunkId(), resolved.id, metadata.recordingId(), tStart, tEnd, "ok", rows, null);
        } catch (Exception error) {
            LOG.warnf(error, "decode failed for %s", metadata.chunkId());
            try {
                catalog.record(
                        metadata.chunkId(),
                        null,
                        metadata.recordingId(),
                        metadata.chunkStartTimeNs(),
                        metadata.chunkStartTimeNs() + metadata.chunkDurationNs(),
                        "error",
                        0L,
                        error.getMessage());
            } catch (SQLException ignored) {
                LOG.warnf(ignored, "could not record decode error for %s", metadata.chunkId());
            }
        }
    }

    private Resolved resolve(ChunkMetadata metadata, JfrChunkReader.ReadChunk chunk, long tStart) throws SQLException {
        boolean provisional = chunk.jvm == null || chunk.jvm.jvmStartTimeMs() == 0L;
        String id;
        long startMs = 0L;
        if (!provisional) {
            startMs = chunk.jvm.jvmStartTimeMs();
            id = JvmInstanceIds.of(
                    metadata.namespace(),
                    metadata.podName(),
                    metadata.containerName(),
                    metadata.podUid(),
                    startMs,
                    chunk.jvm.pid());
            String inherited = catalog.inheritInstance(metadata.recordingId(), tStart);
            if (inherited != null && !inherited.equals(id)) {
                catalog.rekey(inherited, id);
            }
        } else {
            String inherited = catalog.inheritInstance(metadata.recordingId(), tStart);
            id = inherited == null ? JvmInstanceIds.provisional(metadata.recordingId()) : inherited;
            provisional = inherited == null;
        }
        IngestCatalog.InstanceState state = catalog.ensureInstance(
                id, metadata.namespace(), metadata.podName(), metadata.containerName(), metadata.recordingId(), startMs, provisional);
        return new Resolved(id, provisional, state, chunk.jvm);
    }

    private long write(ChunkMetadata metadata, JfrChunkReader.ReadChunk chunk, Resolved resolved, long tStart)
            throws Exception {
        long rows = 0L;
        rows += writeInstance(metadata, chunk, resolved, tStart);
        rows += writeSettings(metadata, chunk, resolved.id);
        rows += writeGauges(metadata, chunk, resolved.id);
        rows += writeCounters(metadata, chunk, resolved.id);
        rows += writeGc(metadata, chunk, resolved.id);
        rows += writePhases(metadata, chunk, resolved.id);
        rows += writeSamples(metadata, chunk, resolved.id);
        rows += writeDurations(metadata, chunk, resolved.id);
        rows += writeStacks(metadata, chunk, resolved.id, tStart);
        rows += writeFrames(metadata, chunk, resolved.id, tStart);
        rows += writeMarkers(metadata, chunk, resolved, tStart);
        return rows;
    }

    private long writeInstance(ChunkMetadata metadata, JfrChunkReader.ReadChunk chunk, Resolved resolved, long tStart)
            throws Exception {
        JfrChunkReader.JvmFacts jvm = resolved.facts;
        JfrChunkReader.ContainerFacts container = chunk.container;
        List<Object> row = identity(metadata, resolved.id, tStart);
        row.add(metadata.clusterId());
        row.add(metadata.podUid());
        row.add(jvm == null ? null : jvm.jvmStartTimeMs());
        row.add(jvm == null ? null : jvm.pid());
        row.add(jvm == null ? null : jvm.jdkVersion());
        row.add(jvm == null ? null : feature(jvm.jdkVersion()));
        row.add(jvm == null ? null : jvm.vmName());
        row.add(resolved.provisional);
        row.add(resolved.state.instanceSeq());
        row.add(resolved.state.restartReasonHint());
        row.add(container == null ? null : container.cpuQuota());
        row.add(container == null ? null : container.cpuSlicePeriod());
        row.add(container == null ? null : container.cpuShares());
        row.add(container == null ? null : container.memoryLimit());
        row.add(container == null ? null : container.effectiveCpus());
        row.add(container == null ? null : container.containerType());
        return emit(metadata, "jvm_instance", resolved.id, tStart, List.of(
                "cluster_id", "pod_uid", "jvm_start", "pid", "jdk_version", "jdk_feature", "vm_name",
                "provisional", "instance_seq", "restart_reason_hint",
                "cpu_quota", "cpu_slice_period", "cpu_shares", "mem_limit", "effective_cpus", "container_type"),
                List.of(row));
    }

    private long writeSettings(ChunkMetadata metadata, JfrChunkReader.ReadChunk chunk, String id) throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        for (JfrChunkReader.SettingRow setting : chunk.settings) {
            List<Object> row = identity(metadata, id, setting.t());
            row.add(setting.eventType());
            row.add(setting.setting());
            row.add(setting.value());
            rows.add(row);
        }
        return emit(metadata, "active_setting", id, metadata.chunkStartTimeNs(), List.of("event_type", "setting", "value"), rows);
    }

    private long writeGauges(ChunkMetadata metadata, JfrChunkReader.ReadChunk chunk, String id) throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        for (JfrChunkReader.GaugePoint gauge : chunk.gauges) {
            List<Object> row = identity(metadata, id, gauge.t());
            row.add(gauge.metric());
            row.add(gauge.value());
            rows.add(row);
        }
        return emit(metadata, "gauge", id, metadata.chunkStartTimeNs(), List.of("metric", "value"), rows);
    }

    private long writeCounters(ChunkMetadata metadata, JfrChunkReader.ReadChunk chunk, String id) throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        for (JfrChunkReader.CounterSample sample : chunk.counters) {
            String series = sample.series() == null ? "" : sample.series();
            IngestCatalog.CounterCursor cursor = catalog.cursor(id, sample.metric(), series);
            Double delta = CounterDeltas.delta(cursor.lastValue(), sample.cumulative());
            Double dt = CounterDeltas.secondsBetween(cursor.lastTNs(), sample.t());
            catalog.saveCursor(id, sample.metric(), series, sample.t(), sample.cumulative());
            if ("container.throttled_slices".equals(sample.metric())) {
                chunk.throttleSlices = delta;
                chunk.throttleT = sample.t();
            } else if ("container.elapsed_slices".equals(sample.metric()) && chunk.throttleT == sample.t() && delta != null && delta > 0.0
                    && chunk.throttleSlices != null) {
                chunk.throttleRatio = chunk.throttleSlices / delta;
                chunk.throttleRatioT = sample.t();
            } else if ("exceptions.throwables".equals(sample.metric()) && delta != null && dt != null && dt > 0.0) {
                chunk.exceptionRate = delta / dt;
                chunk.exceptionRateT = sample.t();
            }
            if (delta == null || dt == null) {
                continue;
            }
            List<Object> row = identity(metadata, id, sample.t());
            row.add(sample.metric());
            row.add(dt);
            row.add(delta);
            rows.add(row);
        }
        return emit(metadata, "counter_delta", id, metadata.chunkStartTimeNs(), List.of("metric", "dt_s", "delta"), rows);
    }

    private long writeGc(ChunkMetadata metadata, JfrChunkReader.ReadChunk chunk, String id) throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        for (JfrChunkReader.GcRow gc : chunk.gcs) {
            List<Object> row = identity(metadata, id, gc.t());
            row.add(gc.gcId());
            row.add(gc.name());
            row.add(gc.cause());
            row.add(gc.kind());
            row.add(gc.sumPauseMs());
            row.add(gc.longestPauseMs());
            rows.add(row);
        }
        return emit(metadata, "gc", id, metadata.chunkStartTimeNs(),
                List.of("gc_id", "name", "cause", "kind", "sum_pause_ms", "longest_pause_ms"), rows);
    }

    private long writePhases(ChunkMetadata metadata, JfrChunkReader.ReadChunk chunk, String id) throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        for (JfrChunkReader.GcPhaseRow phase : chunk.phases) {
            List<Object> row = identity(metadata, id, phase.t());
            row.add(phase.gcId());
            row.add(phase.level());
            row.add(phase.name());
            row.add(phase.startT());
            row.add(phase.durMs());
            row.add(phase.concurrent());
            rows.add(row);
        }
        return emit(metadata, "gc_phase", id, metadata.chunkStartTimeNs(),
                List.of("gc_id", "level", "name", "start_t", "dur_ms", "concurrent"), rows);
    }

    private long writeSamples(ChunkMetadata metadata, JfrChunkReader.ReadChunk chunk, String id) throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        for (JfrChunkReader.SampleRow sample : chunk.samples) {
            List<Object> row = identity(metadata, id, sample.t());
            row.add(sample.kind());
            row.add(sample.source());
            row.add(sample.osThreadId());
            row.add(sample.threadGroup());
            row.add(sample.state());
            row.add(sample.stackId());
            row.add(sample.weight());
            row.add(sample.objectClass());
            row.add(sample.size());
            rows.add(row);
        }
        return emit(metadata, "sample", id, metadata.chunkStartTimeNs(),
                List.of("kind", "source", "os_thread_id", "thread_group", "state", "stack_id", "weight", "object_class", "size"), rows);
    }

    private long writeDurations(ChunkMetadata metadata, JfrChunkReader.ReadChunk chunk, String id) throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        for (JfrChunkReader.DurationRow duration : chunk.durations) {
            List<Object> row = identity(metadata, id, duration.t());
            row.add(duration.type());
            row.add(duration.durMs());
            row.add(duration.osThreadId());
            row.add(duration.threadGroup());
            row.add(duration.stackId());
            row.add(duration.target());
            row.add(duration.monitorClass());
            row.add(duration.parkedClass());
            row.add(duration.previousOwner());
            row.add(duration.bytes());
            row.add(duration.opName());
            row.add(duration.safepointId());
            row.add(null);
            rows.add(row);
        }
        return emit(metadata, "duration_event", id, metadata.chunkStartTimeNs(), List.of(
                "type", "dur_ms", "os_thread_id", "thread_group", "stack_id", "target", "monitor_class", "parked_class",
                "previous_owner", "bytes", "op_name", "safepoint_id", "extra"), rows);
    }

    private long writeStacks(ChunkMetadata metadata, JfrChunkReader.ReadChunk chunk, String id, long tStart) throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        for (JfrChunkReader.StackRow stack : chunk.stacks.values()) {
            List<Object> row = identity(metadata, id, tStart);
            row.add(stack.stackId());
            row.add(stack.frames());
            row.add(stack.leafFrameId());
            row.add(stack.topAppFrameId());
            row.add(stack.depth());
            rows.add(row);
        }
        return emit(metadata, "stack", id, tStart, List.of("stack_id", "frames", "leaf_frame_id", "top_app_frame_id", "depth"), rows);
    }

    private long writeFrames(ChunkMetadata metadata, JfrChunkReader.ReadChunk chunk, String id, long tStart) throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        for (JfrChunkReader.FrameRow frame : chunk.frames.values()) {
            List<Object> row = identity(metadata, id, tStart);
            row.add(frame.frameId());
            row.add(frame.className());
            row.add(frame.method());
            row.add(frame.descriptor());
            row.add(frame.line());
            row.add(frame.type());
            row.add(frame.category());
            rows.add(row);
        }
        return emit(metadata, "frame", id, tStart, List.of("frame_id", "class", "method", "descriptor", "line", "type", "category"), rows);
    }

    private long writeMarkers(ChunkMetadata metadata, JfrChunkReader.ReadChunk chunk, Resolved resolved, long tStart) throws Exception {
        List<MarkerRules.Marker> markers = new ArrayList<>();
        for (JfrChunkReader.GcPhaseRow phase : chunk.phases) {
            if (phase.level() != 0) {
                continue;
            }
            MarkerRules.Marker pause = MarkerRules.gcPause(phase.t(), phase.durMs(), phase.gcId());
            if (pause != null) {
                markers.add(pause);
            }
        }
        for (JfrChunkReader.GcRow gc : chunk.gcs) {
            if ("full".equals(gc.kind())) {
                markers.add(MarkerRules.fullGc(gc.t(), gc.sumPauseMs(), gc.gcId()));
            }
        }
        for (JfrChunkReader.SignalEvent signal : chunk.signals) {
            switch (signal.type()) {
                case "jdk.EvacuationFailed" -> markers.add(MarkerRules.named(signal.t(), "evac_failure", "critical", null, null));
                case "jdk.PromotionFailed" -> markers.add(MarkerRules.named(signal.t(), "promotion_failed", "critical", null, null));
                case "jdk.ZAllocationStall" -> markers.add(MarkerRules.named(signal.t(), "allocation_stall", "warn", null, null));
                case "jdk.CodeCacheFull" -> markers.add(MarkerRules.named(signal.t(), "code_cache_full", "critical", null, null));
                case "jdk.DataLoss" -> markers.add(MarkerRules.named(signal.t(), "data_loss", "warn", null, null));
                default -> {
                }
            }
        }
        if (chunk.oom) {
            markers.add(MarkerRules.named(chunk.oomT > 0L ? chunk.oomT : tStart, "oom", "critical", null, null));
        }
        for (JfrChunkReader.DurationRow duration : chunk.durations) {
            if ("jdk.SafepointBegin".equals(duration.type())) {
                markers.addAll(MarkerRules.safepoint(duration.t(), duration.durMs(), duration.ttspMs()));
            } else if ("jdk.ExecuteVMOperation".equals(duration.type())) {
                MarkerRules.Marker operation = MarkerRules.vmOp(duration.t(), duration.durMs());
                if (operation != null) {
                    markers.add(operation);
                }
            }
        }
        markers.addAll(MarkerRules.deoptBurst(chunk.deoptTimes));
        if (chunk.throttleRatio != null) {
            MarkerRules.Marker throttle = MarkerRules.cpuThrottle(chunk.throttleRatioT, chunk.throttleRatio);
            if (throttle != null) {
                markers.add(throttle);
            }
        }
        if (chunk.exceptionRate != null) {
            MarkerRules.Marker burst = MarkerRules.exceptionBurst(chunk.exceptionRateT, chunk.exceptionRate);
            if (burst != null) {
                markers.add(burst);
            }
        }
        List<List<Object>> rows = new ArrayList<>();
        for (MarkerRules.Marker marker : markers) {
            rows.add(markerRow(metadata, resolved.id, marker));
        }
        rows.addAll(restartRows(metadata, resolved, tStart));
        long written = emit(metadata, "marker", resolved.id, tStart,
                List.of("kind", "severity", "t_end", "magnitude", "unit", "ref"), rows);
        if (resolved.state.instanceSeq() > 1 && resolved.state.previousInstanceId() != null) {
            List<Object> previous = markerRow(metadata, resolved.state.previousInstanceId(), restartMarker(resolved, tStart));
            previous.set(3, resolved.state.previousInstanceId());
            written += emit(metadata, "marker", resolved.state.previousInstanceId(), tStart,
                    List.of("kind", "severity", "t_end", "magnitude", "unit", "ref"), List.of(previous),
                    metadata.chunkId() + "-prev");
        }
        return written;
    }

    private static List<Object> markerRow(ChunkMetadata metadata, String instanceId, MarkerRules.Marker marker) {
        List<Object> row = identity(metadata, instanceId, marker.t());
        row.add(marker.kind());
        row.add(marker.severity());
        row.add(marker.tEnd());
        row.add(marker.magnitude());
        row.add(marker.unit());
        row.add(marker.ref());
        return row;
    }

    private List<List<Object>> restartRows(ChunkMetadata metadata, Resolved resolved, long tStart) {
        if (resolved.state.instanceSeq() <= 1 || resolved.state.previousInstanceId() == null) {
            return List.of();
        }
        return List.of(markerRow(metadata, resolved.id, restartMarker(resolved, tStart)));
    }

    private MarkerRules.Marker restartMarker(Resolved resolved, long tStart) {
        long gapMs = 0L;
        if (resolved.facts != null && resolved.state.previousEndNs() > 0L) {
            gapMs = Math.max(0L, resolved.facts.jvmStartTimeMs() - resolved.state.previousEndNs() / 1_000_000L);
        }
        String ref = "{\"prev_instance\":\"" + resolved.state.previousInstanceId()
                + "\",\"new_instance\":\"" + resolved.id
                + "\",\"instance_seq\":" + resolved.state.instanceSeq()
                + ",\"gap_ms\":" + gapMs
                + ",\"restart_reason_hint\":\"" + resolved.state.restartReasonHint() + "\"}";
        return new MarkerRules.Marker(tStart, tStart, "jvm_restart", "warn", (double) gapMs, "ms", ref);
    }

    public int compact(java.time.Instant now, java.time.Duration minAge) {
        synchronized (catalog) {
            try {
                return HourlyCompaction.compact(catalog.connection(), v2Root, now, minAge);
            } catch (Exception error) {
                throw new IllegalStateException("parquet compaction failed", error);
            }
        }
    }

    private long emit(ChunkMetadata metadata, String table, String instanceId, long tNs, List<String> extra, List<List<Object>> rows)
            throws Exception {
        return emit(metadata, table, instanceId, tNs, extra, rows, metadata.chunkId());
    }

    private long emit(
            ChunkMetadata metadata,
            String table,
            String instanceId,
            long tNs,
            List<String> extra,
            List<List<Object>> rows,
            String fileStem) throws Exception {
        List<String> columns = new ArrayList<>(IDENTITY);
        columns.addAll(extra);
        Path file = ParquetTables.partitionFile(
                v2Root, table, metadata.namespace(), metadata.podName(), metadata.containerName(), instanceId, tNs, fileStem);
        ParquetTables.write(catalog.connection(), file, table, columns, rows);
        return rows.size();
    }

    private static List<Object> identity(ChunkMetadata metadata, String instanceId, long t) {
        List<Object> row = new ArrayList<>();
        row.add(metadata.namespace());
        row.add(metadata.podName());
        row.add(metadata.containerName());
        row.add(instanceId);
        row.add(t);
        return row;
    }

    private static String hint(JfrChunkReader.ReadChunk chunk) {
        if (chunk.oom) {
            return "oom_suspected";
        }
        if (chunk.shutdownReason != null && chunk.shutdownReason.toLowerCase().contains("out of memory")) {
            return "oom_suspected";
        }
        return null;
    }

    private static Integer feature(String version) {
        if (version == null) {
            return null;
        }
        Matcher matcher = FEATURE.matcher(version);
        if (!matcher.find()) {
            return null;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private record Resolved(String id, boolean provisional, IngestCatalog.InstanceState state, JfrChunkReader.JvmFacts facts) {}
}

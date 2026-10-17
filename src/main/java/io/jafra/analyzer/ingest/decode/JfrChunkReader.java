package io.jafra.analyzer.ingest.decode;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.jafra.analyzer.signals.ContainerMath;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordingFile;

/** One pass over a committed chunk with the JDK flight recorder reader. */
public final class JfrChunkReader {
    private JfrChunkReader() {}

    public static ReadChunk read(Path payload) throws IOException {
        ReadChunk chunk = new ReadChunk();
        try (RecordingFile file = new RecordingFile(payload)) {
            while (file.hasMoreEvents()) {
                accept(chunk, file.readEvent());
            }
        }
        EventRows.retagProfilerCpu(chunk);
        return chunk;
    }

    private static void accept(ReadChunk chunk, RecordedEvent event) {
        String type = event.getEventType().getName();
        long t = epochNs(event.getStartTime());
        chunk.seen.add(type);
        chunk.eventsSeen++;
        switch (type) {
            case "jdk.JVMInformation" -> chunk.jvm = jvm(event);
            case "jdk.ActiveSetting" -> {
                SettingRow row = setting(event, t);
                chunk.settings.add(row);
                String blob = ((row.eventType() == null ? "" : row.eventType()) + " " + (row.value() == null ? "" : row.value()))
                        .toLowerCase(Locale.ROOT);
                if (blob.contains("ctimer") || blob.contains("async")) {
                    chunk.sawProfilerEvent = true;
                }
                if ("threshold".equals(row.setting())) {
                    Double ms = ContainerMath.millis(row.value());
                    if (row.eventType() != null && ms != null) {
                        chunk.thresholdsMs.put(row.eventType(), ms);
                    }
                }
            }
            case "jdk.CPULoad" -> {
                number(event, "jvmUser").ifPresent(value -> chunk.gauges.add(new GaugePoint(t, "cpu.jvm_user", value)));
                number(event, "jvmSystem").ifPresent(value -> chunk.gauges.add(new GaugePoint(t, "cpu.jvm_system", value)));
                number(event, "machineTotal").ifPresent(value -> chunk.gauges.add(new GaugePoint(t, "cpu.machine_total", value)));
            }
            case "jdk.GCHeapSummary" -> {
                number(event, "heapUsed").ifPresent(value -> chunk.gauges.add(new GaugePoint(t, "heap.used", value)));
                chunk.heapSummaries.add(heapSummary(event));
            }
            case "jdk.ResidentSetSize" -> firstNumber(event, "size", "residentSet", "rss")
                    .ifPresent(value -> chunk.gauges.add(new GaugePoint(t, "rss", value)));
            case "jdk.ContainerMemoryUsage" -> number(event, "memoryUsage")
                    .ifPresent(value -> chunk.gauges.add(new GaugePoint(t, "container.memory_usage", value)));
            case "jdk.JavaThreadStatistics" -> number(event, "activeCount", "daemonCount")
                    .ifPresent(value -> chunk.gauges.add(new GaugePoint(t, "threads.live", value)));
            case "jdk.ContainerCPUThrottling" -> {
                cumulative(chunk, t, "container.cpu_throttled_ns", event, "cpuThrottledTime");
                cumulative(chunk, t, "container.throttled_slices", event, "cpuThrottledSlices");
                cumulative(chunk, t, "container.elapsed_slices", event, "cpuElapsedSlices");
            }
            case "jdk.ContainerCPUUsage" -> cumulative(chunk, t, "container.cpu_time_ns", event, "cpuTime");
            case "jdk.ThreadAllocationStatistics" -> number(event, "allocated").ifPresent(value -> chunk.counters.add(
                    new CounterSample(t, "alloc.bytes", text(event, "thread") == null ? "" : text(event, "thread"), value)));
            case "jdk.ExceptionStatistics" -> cumulative(chunk, t, "exceptions.throwables", event, "throwables");
            case "jdk.ClassLoadingStatistics" -> {
                cumulative(chunk, t, "classes.loaded", event, "loadedClassCount");
                cumulative(chunk, t, "classes.unloaded", event, "unloadedClassCount");
            }
            case "jdk.GarbageCollection" -> chunk.gcs.add(gc(event, t));
            case "jdk.G1HeapSummary" -> chunk.generations.add(generation(event, "g1"));
            case "jdk.PSHeapSummary" -> chunk.generations.add(generation(event, "parallel"));
            case "jdk.YoungGenerationConfiguration" -> chunk.newRatio = longValue(event, "newRatio");
            case "jdk.YoungGarbageCollection" -> chunk.youngGcs.add(
                    new HeapFrames.YoungGc(longValue(event, "gcId"), integer(event, "tenuringThreshold")));
            case "jdk.ZYoungGarbageCollection" -> chunk.zCollections.add(new HeapFrames.ZCollection(t, longValue(event, "gcId"), "z_young"));
            case "jdk.ZOldGarbageCollection" -> chunk.zCollections.add(new HeapFrames.ZCollection(t, longValue(event, "gcId"), "z_old"));
            case "jdk.G1HeapRegionTypeChange" -> chunk.regions.add(
                    new HeapFrames.RegionEvent(t, longValue(event, "gcId"), text(event, "to", "type"), usedBytes(event)));
            case "jdk.G1HeapRegionInformation" -> chunk.regions.add(
                    new HeapFrames.RegionEvent(t, longValue(event, "gcId"), text(event, "type"), usedBytes(event)));
            case "jdk.GCPhasePause", "jdk.GCPhasePauseLevel1", "jdk.GCPhasePauseLevel2", "jdk.GCPhasePauseLevel3",
                    "jdk.GCPhasePauseLevel4" -> chunk.phases.add(phase(event, t, type));
            case "jdk.Shutdown" -> chunk.shutdownReason = text(event, "reason");
            case "jdk.JavaErrorThrow" -> {
                String thrown = text(event, "thrownClass", "message");
                if (thrown != null && thrown.contains("OutOfMemoryError")) {
                    chunk.oom = true;
                    chunk.oomT = t;
                }
                EventRows.accept(chunk, event, type, t);
            }
            case "jdk.ContainerConfiguration" -> chunk.container = container(event);
            case "jdk.GCConfiguration" -> chunk.gcConfig = new GcConfig(
                    text(event, "youngCollector", "young"), text(event, "oldCollector", "old", "gcName"));
            case "jdk.BooleanFlag", "jdk.StringFlag" -> chunk.flags.add(
                    new FlagRow(text(event, "name"), text(event, "value"), text(event, "origin")));
            case "jdk.LongFlag", "jdk.UnsignedLongFlag" -> {
                Long value = longValue(event, "value");
                chunk.flags.add(new FlagRow(text(event, "name"), value == null ? null : Long.toString(value), text(event, "origin")));
            }
            case "jdk.EvacuationFailed", "jdk.PromotionFailed", "jdk.ZAllocationStall", "jdk.CodeCacheFull", "jdk.DataLoss" ->
                    chunk.signals.add(new SignalEvent(t, type));
            default -> EventRows.accept(chunk, event, type, t);
        }
    }

    private static JvmFacts jvm(RecordedEvent event) {
        Long start = longValue(event, "jvmStartTime");
        Long pid = longValue(event, "pid");
        return new JvmFacts(
                text(event, "jvmVersion", "javaVersion"),
                text(event, "jvmName", "vmName"),
                start == null ? 0L : start,
                pid == null ? 0L : pid);
    }

    private static ContainerFacts container(RecordedEvent event) {
        return new ContainerFacts(
                longValue(event, "cpuQuota"),
                longValue(event, "cpuSlicePeriod"),
                longValue(event, "cpuShares"),
                longValue(event, "memoryLimit"),
                longValue(event, "effectiveCpuCount"),
                text(event, "containerType"));
    }

    private static SettingRow setting(RecordedEvent event, long t) {
        return new SettingRow(t, text(event, "id", "name"), text(event, "setting", "name"), text(event, "value"));
    }

    private static HeapFrames.HeapSummary heapSummary(RecordedEvent event) {
        return new HeapFrames.HeapSummary(
                longValue(event, "gcId"), before(event), longValue(event, "heapUsed"), committed(event));
    }

    private static HeapFrames.GenerationSummary generation(RecordedEvent event, String source) {
        Long eden = firstLong(event, "edenUsedSize", "edenUsed");
        Long survivor = firstLong(event, "survivorUsedSize", "survivorUsed");
        if (survivor == null) {
            Long from = firstLong(event, "fromUsed", "fromSpace");
            Long to = firstLong(event, "toUsed", "toSpace");
            if (from != null || to != null) {
                survivor = (from == null ? 0L : from) + (to == null ? 0L : to);
            }
        }
        Long old = firstLong(event, "oldUsedSize", "oldUsed", "oldGenUsedSize");
        return new HeapFrames.GenerationSummary(longValue(event, "gcId"), before(event), source, eden, survivor, old);
    }

    private static boolean before(RecordedEvent event) {
        String when = text(event, "when");
        return when == null || when.toLowerCase(Locale.ROOT).contains("before");
    }

    private static Long committed(RecordedEvent event) {
        if (!event.hasField("heapSpace")) {
            return firstLong(event, "committedSize", "committed");
        }
        Object space = event.getValue("heapSpace");
        if (space instanceof RecordedObject object) {
            return firstLong(object, "committedSize", "committed");
        }
        return null;
    }

    private static long usedBytes(RecordedEvent event) {
        Long used = firstLong(event, "used");
        return used == null ? 0L : used;
    }

    private static Long firstLong(RecordedEvent event, String... fields) {
        for (String field : fields) {
            if (!event.hasField(field)) {
                continue;
            }
            Long value = asLong(event.getValue(field));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Long firstLong(RecordedObject object, String... fields) {
        for (String field : fields) {
            if (!object.hasField(field)) {
                continue;
            }
            Long value = asLong(object.getValue(field));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof RecordedObject object) {
            return firstLong(object, "used", "usedSize");
        }
        return null;
    }

    private static Integer integer(RecordedEvent event, String field) {
        Long value = longValue(event, field);
        return value == null ? null : value.intValue();
    }

    private static GcRow gc(RecordedEvent event, long t) {
        String name = text(event, "name");
        return new GcRow(
                t,
                longValue(event, "gcId"),
                name,
                text(event, "cause"),
                kind(name),
                durationMs(event, "sumOfPauses"),
                durationMs(event, "longestPause"));
    }

    private static GcPhaseRow phase(RecordedEvent event, long t, String type) {
        int level = 0;
        if (type.endsWith("Level1")) {
            level = 1;
        } else if (type.endsWith("Level2")) {
            level = 2;
        } else if (type.endsWith("Level3")) {
            level = 3;
        } else if (type.endsWith("Level4")) {
            level = 4;
        }
        double dur = event.getDuration() == null ? 0.0 : event.getDuration().toNanos() / 1_000_000.0;
        return new GcPhaseRow(t, longValue(event, "gcId"), level, text(event, "name"), t, dur, false);
    }

    private static void cumulative(ReadChunk chunk, long t, String metric, RecordedEvent event, String field) {
        number(event, field).ifPresent(value -> chunk.counters.add(new CounterSample(t, metric, "", value)));
    }

    private static String kind(String name) {
        if (name == null) {
            return "old";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("young")) {
            return "young";
        }
        if (lower.contains("mixed")) {
            return "mixed";
        }
        if (lower.contains("full")) {
            return "full";
        }
        if (lower.contains("concurrent")) {
            return "concurrent_start";
        }
        return "old";
    }

    private static Double durationMs(RecordedEvent event, String field) {
        if (!event.hasField(field)) {
            return null;
        }
        Object value = event.getValue(field);
        if (value instanceof Duration duration) {
            return duration.toNanos() / 1_000_000.0;
        }
        java.util.OptionalDouble parsed = number(event, field);
        return parsed.isPresent() ? parsed.getAsDouble() : null;
    }

    private static java.util.OptionalDouble number(RecordedEvent event, String... fields) {
        for (String field : fields) {
            if (!event.hasField(field)) {
                continue;
            }
            Object value = event.getValue(field);
            if (value instanceof Number number) {
                return java.util.OptionalDouble.of(number.doubleValue());
            }
            if (value instanceof Duration duration) {
                return java.util.OptionalDouble.of(duration.toNanos());
            }
        }
        return java.util.OptionalDouble.empty();
    }

    private static java.util.OptionalDouble firstNumber(RecordedEvent event, String... fields) {
        return number(event, fields);
    }

    private static Long longValue(RecordedEvent event, String field) {
        if (!event.hasField(field)) {
            return null;
        }
        Object value = event.getValue(field);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof Instant instant) {
            return instant.toEpochMilli();
        }
        return null;
    }

    private static String text(RecordedEvent event, String... fields) {
        for (String field : fields) {
            if (!event.hasField(field)) {
                continue;
            }
            Object value = event.getValue(field);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static long epochNs(Instant instant) {
        if (instant == null) {
            return 0L;
        }
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    public static final class ReadChunk {
        JvmFacts jvm;
        int eventsSeen;
        ContainerFacts container;
        GcConfig gcConfig;
        final Set<String> seen = new LinkedHashSet<>();
        final List<FlagRow> flags = new ArrayList<>();
        final Map<String, Double> thresholdsMs = new LinkedHashMap<>();
        String shutdownReason;
        boolean oom;
        long oomT;
        boolean sawProfilerEvent;
        final List<SettingRow> settings = new ArrayList<>();
        final List<GaugePoint> gauges = new ArrayList<>();
        final List<CounterSample> counters = new ArrayList<>();
        final List<GcRow> gcs = new ArrayList<>();
        final List<GcPhaseRow> phases = new ArrayList<>();
        final List<HeapFrames.HeapSummary> heapSummaries = new ArrayList<>();
        final List<HeapFrames.GenerationSummary> generations = new ArrayList<>();
        final List<HeapFrames.YoungGc> youngGcs = new ArrayList<>();
        final List<HeapFrames.ZCollection> zCollections = new ArrayList<>();
        final List<HeapFrames.RegionEvent> regions = new ArrayList<>();
        Long newRatio;
        final List<SampleRow> samples = new ArrayList<>();
        final List<DurationRow> durations = new ArrayList<>();
        final List<SignalEvent> signals = new ArrayList<>();
        final List<Long> deoptTimes = new ArrayList<>();
        final Map<String, StackRow> stacks = new LinkedHashMap<>();
        final Map<String, FrameRow> frames = new LinkedHashMap<>();
        Double throttleSlices;
        long throttleT;
        Double throttleRatio;
        long throttleRatioT;
        Double exceptionRate;
        long exceptionRateT;
    }

    public record JvmFacts(String jdkVersion, String vmName, long jvmStartTimeMs, long pid) {}

    public record ContainerFacts(
            Long cpuQuota, Long cpuSlicePeriod, Long cpuShares, Long memoryLimit, Long effectiveCpus, String containerType) {}

    public record GcConfig(String young, String old) {}

    public record FlagRow(String name, String value, String origin) {}

    public record SettingRow(long t, String eventType, String setting, String value) {}

    public record GaugePoint(long t, String metric, double value) {}

    public record CounterSample(long t, String metric, String series, double cumulative) {}

    public record GcRow(long t, Long gcId, String name, String cause, String kind, Double sumPauseMs, Double longestPauseMs) {}

    public record GcPhaseRow(long t, Long gcId, int level, String name, long startT, double durMs, boolean concurrent) {}

    public record SampleRow(
            long t, String kind, String source, Long osThreadId, String threadGroup, String state, String stackId, Double weight,
            String objectClass, Long size) {
        SampleRow withSource(String updated) {
            return new SampleRow(t, kind, updated, osThreadId, threadGroup, state, stackId, weight, objectClass, size);
        }
    }

    public record DurationRow(
            long t, String type, double durMs, Long osThreadId, String threadGroup, String stackId, String target, String monitorClass,
            String parkedClass, String previousOwner, Long bytes, String opName, Long safepointId, Double ttspMs) {}

    public record StackRow(String stackId, String frames, String leafFrameId, String topAppFrameId, int depth) {}

    public record FrameRow(String frameId, String className, String method, String descriptor, int line, String type, String category) {}

    public record SignalEvent(long t, String type) {}
}

package io.jafra.analyzer.v2.rollup;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jdk.jfr.EventType;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/** Reads exactly one chunk file into {@link ChunkFacts}. Never concatenates chunks. */
public final class ChunkReader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern VERSION_MAJOR = Pattern.compile("(\\d+)(?:\\.(\\d+))?");

    private final double lockIntervalMs;

    public ChunkReader(double lockIntervalMs) {
        this.lockIntervalMs = lockIntervalMs;
    }

    public ChunkFacts read(RecordingFile file, long chunkStartMs, long chunkEndMs) throws java.io.IOException {
        Map<Long, String> typeNames = new LinkedHashMap<>();
        for (EventType type : file.readEventTypes()) {
            typeNames.put(type.getId(), type.getName());
        }
        List<Seen> events = new ArrayList<>();
        while (file.hasMoreEvents()) {
            RecordedEvent event = file.readEvent();
            if (event == null) {
                continue;
            }
            events.add(copy(event));
        }
        return assemble(events, typeNames, chunkStartMs, chunkEndMs);
    }

    public ChunkFacts assemble(List<Seen> events, Map<Long, String> typeNames, long chunkStartMs, long chunkEndMs) {
        long from = chunkStartMs;
        long to = chunkEndMs;
        if (to <= from) {
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            for (Seen event : events) {
                min = Math.min(min, event.startMs);
                max = Math.max(max, event.endMs);
            }
            if (min != Long.MAX_VALUE) {
                from = min;
                to = Math.max(max, min + 1);
            }
        }
        double dt = Math.max(0d, (to - from) / 1000d);
        if (dt <= 0d) {
            dt = 5d;
        }
        Seen jvm = last(events, "jdk.JVMInformation");
        String version = jvm == null ? "" : text(jvm, "jvmVersion");
        int major = major(version);
        if (major == 0) {
            major = Runtime.version().feature();
        }
        long start = jvm == null ? from : instantMillis(jvm, "jvmStartTime", from);
        String arguments = jvm == null ? "" : text(jvm, "jvmArguments");
        Map<String, Double> thresholds = thresholds(events, typeNames);
        String profile = profile(thresholds.get("jdk.ExecuteVMOperation"));
        String settings = settings(jvm, version, start, arguments, profile, thresholds, events, typeNames.values());
        ChunkFacts facts = new ChunkFacts(events, dt, from, to, major, version, start, profile, settings, thresholds, lockIntervalMs);
        facts.eventTypes = java.util.Set.copyOf(typeNames.values());
        return facts;
    }

    private static Seen copy(RecordedEvent event) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (ValueDescriptor field : event.getFields()) {
            copyValue(field.getName(), event.getValue(field.getName()), values);
        }
        Duration duration = event.getDuration();
        if (duration != null && !duration.isNegative()) {
            values.putIfAbsent("duration", duration.toNanos() / 1000d);
        }
        Instant start = event.getStartTime();
        long startMs = start == null ? 0L : start.toEpochMilli();
        long endMs = startMs;
        if (duration != null && !duration.isNegative()) {
            endMs = startMs + duration.toMillis();
        }
        RecordedThread thread = event.getThread();
        String threadName = thread == null ? "" : thread.getJavaName();
        return new Seen(event.getEventType().getName(), startMs, endMs, threadName, values, frames(event.getStackTrace()));
    }

    private static void copyValue(String name, Object value, Map<String, Object> out) {
        if (value == null || name == null) {
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            out.put(name, value instanceof Number number ? number.doubleValue() : value.toString());
            return;
        }
        if (value instanceof String || value instanceof Enum<?>) {
            out.put(name, value.toString());
            return;
        }
        if (value instanceof Duration duration) {
            out.put(name, duration.toNanos() / 1000d);
            return;
        }
        if (value instanceof Instant instant) {
            out.put(name, (double) instant.toEpochMilli());
            return;
        }
        if (value instanceof RecordedClass recordedClass) {
            out.put(name, recordedClass.getName());
            return;
        }
        if (value instanceof RecordedThread recordedThread) {
            out.put(name, recordedThread.getJavaName());
            return;
        }
        if (value instanceof RecordedMethod method) {
            out.put(name, methodName(method));
            return;
        }
        if (value instanceof RecordedObject object && !(value instanceof RecordedEvent)) {
            for (ValueDescriptor field : object.getFields()) {
                copyValue(name + "." + field.getName(), object.getValue(field.getName()), out);
            }
            return;
        }
        out.put(name, value.toString());
    }

    private static List<Frame> frames(RecordedStackTrace stack) {
        if (stack == null) {
            return List.of();
        }
        List<RecordedFrame> recorded = stack.getFrames();
        if (recorded == null || recorded.isEmpty()) {
            return List.of();
        }
        List<Frame> frames = new ArrayList<>();
        int limit = Math.min(recorded.size(), Frames.MAX_DEPTH);
        for (int i = 0; i < limit; i++) {
            RecordedFrame frame = recorded.get(i);
            String type = frame.getType() == null ? "" : frame.getType();
            RecordedMethod method = frame.getMethod();
            String name = method == null ? type : methodName(method);
            int line = frame.getLineNumber();
            frames.add(new Frame(name, Frames.category(type, name), type, line));
        }
        return List.copyOf(frames);
    }

    private static String methodName(RecordedMethod method) {
        String owner = method.getType() == null ? "" : method.getType().getName();
        String name = method.getName() == null ? "" : method.getName();
        if (owner.isBlank()) {
            return name;
        }
        return owner + "." + name;
    }

    private static Map<String, Double> thresholds(List<Seen> events, Map<Long, String> typeNames) {
        Map<String, Double> found = new LinkedHashMap<>();
        for (Seen event : events) {
            if (!"jdk.ActiveSetting".equals(event.type)) {
                continue;
            }
            String setting = event.text("name");
            if (setting == null || !setting.equalsIgnoreCase("threshold")) {
                continue;
            }
            long id = event.id("id");
            String type = typeNames.get(id);
            if (type == null) {
                continue;
            }
            Double ms = thresholdMs(event.text("value"));
            if (ms != null) {
                found.put(type, ms);
            }
        }
        return Map.copyOf(found);
    }

    public static Double thresholdMs(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().toLowerCase(Locale.ROOT).split("\\s+");
        double number;
        try {
            number = Double.parseDouble(parts[0]);
        } catch (NumberFormatException error) {
            return null;
        }
        if (parts.length < 2) {
            return number;
        }
        return switch (parts[1]) {
            case "ns" -> number / 1_000_000d;
            case "us", "µs" -> number / 1_000d;
            case "ms" -> number;
            case "s" -> number * 1_000d;
            default -> number;
        };
    }

    /** 0 ms aggressive, 1 ms medium, 10 ms low. Never "high". */
    static String profile(Double executeVmOperationMs) {
        if (executeVmOperationMs == null) {
            return "custom";
        }
        if (Math.abs(executeVmOperationMs) < 0.001d) {
            return "aggressive";
        }
        if (Math.abs(executeVmOperationMs - 1d) < 0.05d) {
            return "medium";
        }
        if (Math.abs(executeVmOperationMs - 10d) < 0.05d) {
            return "low";
        }
        return "custom";
    }

    static int major(String version) {
        if (version == null || version.isBlank()) {
            return 0;
        }
        Matcher matcher = VERSION_MAJOR.matcher(version);
        if (!matcher.find()) {
            return 0;
        }
        int first = Integer.parseInt(matcher.group(1));
        if (first == 1 && matcher.group(2) != null) {
            return Integer.parseInt(matcher.group(2));
        }
        return first;
    }

    private static String settings(
            Seen jvm,
            String version,
            long startMs,
            String arguments,
            String profile,
            Map<String, Double> thresholds,
            List<Seen> events,
            java.util.Collection<String> eventTypes) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> jvmNode = new LinkedHashMap<>();
        jvmNode.put("version", version == null ? "" : version);
        jvmNode.put("name", jvm == null ? "" : text(jvm, "jvmName"));
        jvmNode.put("start", Instant.ofEpochMilli(startMs).toString());
        jvmNode.put("arguments", arguments == null ? "" : arguments);
        root.put("jvm", jvmNode);
        root.put("heap", heap(events, arguments));
        root.put("gc", gc(events, arguments));
        root.put("jit", jit(events));
        root.put("code_cache", codeCache(events));
        root.put("container", container(events));
        root.put("host", host(events));
        root.put("flags", flags(arguments));
        Map<String, Object> recording = new LinkedHashMap<>();
        recording.put("profile", profile);
        Map<String, String> thresholdText = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : thresholds.entrySet()) {
            thresholdText.put(entry.getKey(), entry.getValue() + " ms");
        }
        recording.put("thresholds", thresholdText);
        root.put("recording", recording);
        root.put("k8s", Map.of("namespace", "", "pod", "", "container", ""));
        root.put("nmt", nmtEnabled(arguments) ? "enabled" : "off");
        root.put("declared_event_types", eventTypes == null ? List.of() : List.copyOf(eventTypes));
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException error) {
            return "{}";
        }
    }

    private static Map<String, Object> heap(List<Seen> events, String arguments) {
        Seen config = last(events, "jdk.GCHeapConfiguration");
        Seen young = last(events, "jdk.YoungGenerationConfiguration");
        Seen survivor = last(events, "jdk.GCSurvivorConfiguration");
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("initial", config == null ? null : config.num("initialSize"));
        node.put("max", config == null ? null : config.num("maxSize"));
        node.put("compressed_oops", config == null ? null : text(config, "compressedOopsMode"));
        node.put("young_min", young == null ? null : young.num("minSize"));
        node.put("young_max", young == null ? null : young.num("maxSize"));
        node.put("tenuring_threshold", survivor == null ? null : survivor.num("maxTenuringThreshold"));
        node.put("xmx", flagValue(arguments, "-Xmx", "-XX:MaxHeapSize"));
        node.put("max_ram_percentage", flagValue(arguments, "-XX:MaxRAMPercentage"));
        return node;
    }

    private static Map<String, Object> gc(List<Seen> events, String arguments) {
        Seen config = last(events, "jdk.GCConfiguration");
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("young", config == null ? null : text(config, "youngCollector"));
        node.put("old", config == null ? null : text(config, "oldCollector"));
        node.put("parallel_threads", config == null ? null : config.num("parallelGCThreads"));
        node.put("concurrent_threads", config == null ? null : config.num("concurrentGCThreads"));
        node.put("pause_target_ms", config == null ? null : config.num("pauseTarget"));
        boolean disabled = arguments != null && arguments.contains("-XX:+DisableExplicitGC");
        node.put("explicit_gc_disabled", disabled);
        return node;
    }

    private static Map<String, Object> jit(List<Seen> events) {
        Seen config = last(events, "jdk.CompilerConfiguration");
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("compiler_threads", config == null ? null : config.num("threadCount"));
        node.put("tiered", config == null ? null : text(config, "tieredCompilation"));
        return node;
    }

    private static Map<String, Object> codeCache(List<Seen> events) {
        Seen config = last(events, "jdk.CodeCacheConfiguration");
        Map<String, Object> node = new LinkedHashMap<>();
        if (config != null) {
            putNum(node, "reserved", config, "reservedSize");
            putNum(node, "non_nmethod", config, "nonNMethodSize");
            putNum(node, "profiled", config, "profiledSize");
            putNum(node, "non_profiled", config, "nonProfiledSize");
        }
        return node;
    }

    private static Map<String, Object> container(List<Seen> events) {
        Seen config = last(events, "jdk.ContainerConfiguration");
        Map<String, Object> node = new LinkedHashMap<>();
        if (config != null) {
            putNum(node, "memory_limit", config, "memoryLimit");
            putNum(node, "cpu_quota", config, "cpuQuota");
            Double period = config.num("cpuSlicePeriod");
            if (period == null) {
                period = config.num("cpuPeriod");
            }
            node.put("cpu_period", period);
            putNum(node, "effective_cpus", config, "effectiveCpuCount");
            putNum(node, "host_memory", config, "hostTotalMemory");
        }
        return node;
    }

    private static Map<String, Object> host(List<Seen> events) {
        Seen cpu = last(events, "jdk.CPUInformation");
        Seen os = last(events, "jdk.OSInformation");
        Seen virt = last(events, "jdk.VirtualizationInformation");
        Seen memory = last(events, "jdk.PhysicalMemory");
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("cpu", cpu == null ? null : text(cpu, "cpu"));
        node.put("cores", cpu == null ? null : cpu.num("cores"));
        node.put("os", os == null ? null : text(os, "osVersion"));
        node.put("virtualization", virt == null ? null : text(virt, "name"));
        node.put("physical_memory", memory == null ? null : memory.num("totalSize"));
        return node;
    }

    static Map<String, String> flags(String arguments) {
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put("MaxMetaspaceSize", orNotSet(flagValue(arguments, "-XX:MaxMetaspaceSize")));
        flags.put("MaxDirectMemorySize", orNotSet(flagValue(arguments, "-XX:MaxDirectMemorySize")));
        flags.put("NativeMemoryTracking", orNotSet(flagValue(arguments, "-XX:NativeMemoryTracking")));
        flags.put("source", "jvmArguments");
        return flags;
    }

    static boolean nmtEnabled(String arguments) {
        String value = flagValue(arguments, "-XX:NativeMemoryTracking");
        return value != null && !value.equalsIgnoreCase("off");
    }

    static String flagValue(String arguments, String... names) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        for (String token : arguments.split("\\s+")) {
            for (String name : names) {
                if (token.startsWith(name + "=")) {
                    return token.substring(name.length() + 1);
                }
                if (token.startsWith("-Xmx") && "-Xmx".equals(name) && token.length() > 4) {
                    return token.substring(4);
                }
            }
        }
        return null;
    }

    private static String orNotSet(String value) {
        return value == null || value.isBlank() ? "not set" : value;
    }

    private static void putNum(Map<String, Object> node, String key, Seen event, String field) {
        node.put(key, event.num(field));
    }

    private static Seen last(List<Seen> events, String type) {
        Seen found = null;
        for (Seen event : events) {
            if (type.equals(event.type)) {
                found = event;
            }
        }
        return found;
    }

    private static String text(Seen event, String field) {
        String value = event.text(field);
        return value == null ? "" : value;
    }

    private static long instantMillis(Seen event, String field, long fallback) {
        Double value = event.num(field);
        if (value == null) {
            return fallback;
        }
        return value.longValue();
    }
}

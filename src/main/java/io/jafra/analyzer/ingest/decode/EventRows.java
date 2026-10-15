package io.jafra.analyzer.ingest.decode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.jafra.analyzer.recordings.StackIds;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;

/** Maps sample, duration, and stack events into rows for one chunk. */
public final class EventRows {
    private static final Set<String> SAMPLES = Set.of(
            "jdk.ExecutionSample",
            "jdk.NativeMethodSample",
            "profiler.ExecutionSample",
            "profiler.WallClockSample",
            "jdk.ObjectAllocationInNewTLAB",
            "jdk.ObjectAllocationOutsideTLAB",
            "jdk.ObjectAllocationSample",
            "profiler.ObjectAllocationInNewTLAB",
            "profiler.ObjectAllocationOutsideTLAB",
            "jdk.JavaMonitorEnter",
            "jdk.JavaMonitorWait",
            "jdk.ThreadPark",
            "profiler.NativeLock",
            "profiler.Malloc",
            "profiler.Free",
            "jdk.ThreadCPULoad");

    private static final Set<String> DURATIONS = Set.of(
            "jdk.FileRead",
            "jdk.FileWrite",
            "jdk.FileForce",
            "jdk.SocketRead",
            "jdk.SocketWrite",
            "jdk.JavaMonitorEnter",
            "jdk.JavaMonitorWait",
            "jdk.ThreadPark",
            "profiler.NativeLock",
            "jdk.ThreadSleep",
            "jdk.SafepointBegin",
            "jdk.ExecuteVMOperation",
            "jdk.Compilation",
            "jdk.Deoptimization");

    private EventRows() {}

    public static boolean isSample(String type) {
        return SAMPLES.contains(type);
    }

    public static boolean isDuration(String type) {
        return DURATIONS.contains(type);
    }

    public static void accept(JfrChunkReader.ReadChunk chunk, RecordedEvent event, String type, long t) {
        if (type.startsWith("profiler.")) {
            chunk.sawProfilerEvent = true;
        }
        if (isSample(type)) {
            chunk.samples.add(sample(chunk, event, type, t));
        }
        if (isDuration(type)) {
            chunk.durations.add(duration(chunk, event, type, t));
        }
        if ("jdk.Deoptimization".equals(type)) {
            chunk.deoptTimes.add(t);
        }
    }

    public static void retagProfilerCpu(JfrChunkReader.ReadChunk chunk) {
        if (!chunk.sawProfilerEvent) {
            return;
        }
        for (int i = 0; i < chunk.samples.size(); i++) {
            JfrChunkReader.SampleRow sample = chunk.samples.get(i);
            if ("cpu".equals(sample.kind()) && "jfr".equals(sample.source())) {
                chunk.samples.set(i, sample.withSource("async_profiler"));
            }
        }
    }

    private static JfrChunkReader.SampleRow sample(JfrChunkReader.ReadChunk chunk, RecordedEvent event, String type, long t) {
        RecordedThread thread = thread(event);
        String stackId = captureStack(chunk, event, thread);
        return new JfrChunkReader.SampleRow(
                t,
                kind(type),
                type.startsWith("profiler.") ? "async_profiler" : "jfr",
                thread == null ? null : ThreadKeys.osThreadId(thread.getOSThreadId(), thread.getJavaThreadId()),
                ThreadKeys.group(thread == null ? null : thread.getJavaName()),
                text(event, "state"),
                stackId,
                weight(event, type),
                className(event, "objectClass", "class"),
                longValue(event, "allocationSize", "size", "requestedSize"));
    }

    private static JfrChunkReader.DurationRow duration(JfrChunkReader.ReadChunk chunk, RecordedEvent event, String type, long t) {
        RecordedThread thread = thread(event);
        double durMs = event.getDuration() == null ? 0.0 : event.getDuration().toNanos() / 1_000_000.0;
        return new JfrChunkReader.DurationRow(
                t,
                type,
                durMs,
                thread == null ? null : ThreadKeys.osThreadId(thread.getOSThreadId(), thread.getJavaThreadId()),
                ThreadKeys.group(thread == null ? null : thread.getJavaName()),
                captureStack(chunk, event, thread),
                target(event),
                className(event, "monitorClass"),
                className(event, "parkedClass"),
                text(event, "previousOwner"),
                longValue(event, "bytesRead", "bytesWritten", "bytes"),
                text(event, "operation", "name"),
                longValue(event, "safepointId"),
                ttspMs(event));
    }

    private static String captureStack(JfrChunkReader.ReadChunk chunk, RecordedEvent event, RecordedThread thread) {
        RecordedStackTrace trace = event.getStackTrace();
        if (trace == null || trace.getFrames() == null || trace.getFrames().isEmpty()) {
            return null;
        }
        String threadName = thread == null ? null : thread.getJavaName();
        List<StackIds.Frame> idFrames = new ArrayList<>();
        List<String> frameIds = new ArrayList<>();
        String leaf = null;
        String topApp = null;
        for (RecordedFrame frame : trace.getFrames()) {
            RecordedMethod method = frame.getMethod();
            String className = method == null || method.getType() == null ? "" : method.getType().getName();
            String methodName = method == null ? "" : method.getName();
            String descriptor = method == null ? "" : method.getDescriptor();
            int line = frame.getLineNumber();
            String frameType = frame.getType() == null ? "" : frame.getType();
            String category = FrameCategories.of(className, methodName, threadName);
            String frameId = frameId(className, methodName, descriptor, line, frameType);
            chunk.frames.putIfAbsent(frameId, new JfrChunkReader.FrameRow(
                    frameId, className, methodName, descriptor == null ? "" : descriptor, line, frameType, category));
            frameIds.add(frameId);
            idFrames.add(new StackIds.Frame(methodName, className, line, frameType));
            if (leaf == null) {
                leaf = frameId;
            }
            if (topApp == null && !StackIds.platformClass(className)) {
                topApp = frameId;
            }
        }
        String stackId = StackIds.idOf(idFrames);
        if (stackId == null) {
            return null;
        }
        if (topApp == null) {
            topApp = leaf;
        }
        chunk.stacks.putIfAbsent(stackId, new JfrChunkReader.StackRow(
                stackId, String.join(",", frameIds), leaf, topApp, frameIds.size()));
        return stackId;
    }

    private static String kind(String type) {
        return switch (type) {
            case "profiler.WallClockSample" -> "wall";
            case "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB", "jdk.ObjectAllocationSample",
                    "profiler.ObjectAllocationInNewTLAB", "profiler.ObjectAllocationOutsideTLAB" -> "alloc";
            case "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark" -> "lock";
            case "profiler.NativeLock" -> "native_lock";
            case "profiler.Malloc" -> "native_malloc";
            case "profiler.Free" -> "native_free";
            case "jdk.ThreadCPULoad" -> "cputime";
            default -> "cpu";
        };
    }

    private static Double weight(RecordedEvent event, String type) {
        java.util.OptionalDouble explicit = number(event, "weight", "samples");
        if (explicit.isPresent()) {
            return explicit.getAsDouble();
        }
        Long size = longValue(event, "allocationSize", "size", "requestedSize");
        if (size != null && (type.contains("Allocation") || type.contains("Malloc") || type.contains("Free"))) {
            return size.doubleValue();
        }
        return 1.0;
    }

    private static String target(RecordedEvent event) {
        String path = text(event, "path");
        if (path != null) {
            return path;
        }
        String host = text(event, "host", "address");
        Long port = longValue(event, "port");
        if (host == null) {
            return null;
        }
        return port == null ? host : host + ":" + port;
    }

    private static Double ttspMs(RecordedEvent event) {
        if (!event.hasField("timeToSafePoint")) {
            return null;
        }
        Object value = event.getValue("timeToSafePoint");
        if (value instanceof Duration duration) {
            return duration.toNanos() / 1_000_000.0;
        }
        java.util.OptionalDouble parsed = number(event, "timeToSafePoint");
        return parsed.isPresent() ? parsed.getAsDouble() : null;
    }

    private static RecordedThread thread(RecordedEvent event) {
        if (event.getThread() != null) {
            return event.getThread();
        }
        for (String field : List.of("sampledThread", "eventThread")) {
            if (event.hasField(field) && event.getValue(field) instanceof RecordedThread recorded) {
                return recorded;
            }
        }
        return null;
    }

    private static String className(RecordedEvent event, String... fields) {
        for (String field : fields) {
            if (!event.hasField(field)) {
                continue;
            }
            Object value = event.getValue(field);
            if (value instanceof RecordedClass recorded) {
                return recorded.getName();
            }
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
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
            if (value instanceof RecordedThread thread) {
                return thread.getJavaName();
            }
            String text = String.valueOf(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static Long longValue(RecordedEvent event, String... fields) {
        for (String field : fields) {
            if (!event.hasField(field)) {
                continue;
            }
            Object value = event.getValue(field);
            if (value instanceof Number number) {
                return number.longValue();
            }
        }
        return null;
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
        }
        return java.util.OptionalDouble.empty();
    }

    private static String frameId(String className, String method, String descriptor, int line, String type) {
        String canonical = (className == null ? "" : className) + '\t'
                + (method == null ? "" : method) + '\t'
                + (descriptor == null ? "" : descriptor) + '\t'
                + line + '\t'
                + (type == null ? "" : type);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            long value = 0L;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (digest[i] & 0xffL);
            }
            String hex = Long.toUnsignedString(value, 16);
            return "0".repeat(16 - hex.length()) + hex;
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required", error);
        }
    }
}

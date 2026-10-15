package io.jafra.analyzer.metrics;

import java.util.List;
import java.util.Map;

/** Catalogue of rollup metrics. Native period is the smallest step a later read should return. */
public final class MetricRegistry {
    public static final List<Metric> ALL = List.of(
            metric("cpu.jvm_user", "percent", "gauge", "1s", 17, "jdk.CPULoad"),
            metric("cpu.jvm_system", "percent", "gauge", "1s", 17, "jdk.CPULoad"),
            metric("cpu.machine_total", "percent", "gauge", "1s", 17, "jdk.CPULoad"),
            metric("heap.used", "bytes", "gauge", "1s", 17, "jdk.GCHeapSummary"),
            metric("rss", "bytes", "gauge", "1s", 21, "jdk.ResidentSetSize"),
            metric("container.memory_usage", "bytes", "gauge", "1s", 17, "jdk.ContainerMemoryUsage"),
            metric("threads.live", "count", "gauge", "1s", 17, "jdk.JavaThreadStatistics"),
            metric("container.cpu_throttled_ns", "ns", "counter", "1s", 17, "jdk.ContainerCPUThrottling"),
            metric("container.throttled_slices", "count", "counter", "1s", 17, "jdk.ContainerCPUThrottling"),
            metric("container.elapsed_slices", "count", "counter", "1s", 17, "jdk.ContainerCPUThrottling"),
            metric("container.cpu_time_ns", "ns", "counter", "1s", 17, "jdk.ContainerCPUUsage"),
            metric("alloc.bytes", "bytes", "counter", "1s", 17, "jdk.ThreadAllocationStatistics"),
            metric("exceptions.throwables", "count", "counter", "1s", 17, "jdk.ExceptionStatistics"),
            metric("classes.loaded", "count", "counter", "1s", 17, "jdk.ClassLoadingStatistics"),
            metric("classes.unloaded", "count", "counter", "1s", 17, "jdk.ClassLoadingStatistics"),
            metric("gc.pause_ms", "ms", "duration", "1ms", 17, "jdk.GCPhasePause"),
            metric("io.file_read_ms", "ms", "duration", "1ms", 17, "jdk.FileRead"),
            metric("io.file_write_ms", "ms", "duration", "1ms", 17, "jdk.FileWrite"),
            metric("io.file_force_ms", "ms", "duration", "1ms", 17, "jdk.FileForce"),
            metric("io.socket_read_ms", "ms", "duration", "1ms", 17, "jdk.SocketRead"),
            metric("io.socket_write_ms", "ms", "duration", "1ms", 17, "jdk.SocketWrite"),
            metric("lock.enter_ms", "ms", "duration", "1ms", 17, "jdk.JavaMonitorEnter"),
            metric("lock.wait_ms", "ms", "duration", "1ms", 17, "jdk.JavaMonitorWait"),
            metric("lock.park_ms", "ms", "duration", "1ms", 17, "jdk.ThreadPark"),
            metric("lock.native_ms", "ms", "duration", "1ms", 17, "profiler.NativeLock"),
            metric("safepoint.ms", "ms", "duration", "1ms", 17, "jdk.SafepointBegin"),
            metric("vm_op.ms", "ms", "duration", "1ms", 17, "jdk.ExecuteVMOperation"),
            metric("compile.ms", "ms", "duration", "1ms", 17, "jdk.Compilation"),
            metric("deopt.ms", "ms", "duration", "1ms", 17, "jdk.Deoptimization"),
            metric("sample.cpu", "count", "sample-share", "1s", 17, "jdk.ExecutionSample", "profiler.ExecutionSample"),
            metric("sample.wall", "count", "sample-share", "1s", 17, "profiler.WallClockSample"),
            metric("sample.alloc", "count", "sample-share", "1s", 17, "jdk.ObjectAllocationSample"),
            metric("sample.lock", "count", "sample-share", "1s", 17, "jdk.JavaMonitorEnter"));

    private static final Map<String, String> DURATION_METRIC = Map.ofEntries(
            Map.entry("jdk.FileRead", "io.file_read_ms"),
            Map.entry("jdk.FileWrite", "io.file_write_ms"),
            Map.entry("jdk.FileForce", "io.file_force_ms"),
            Map.entry("jdk.SocketRead", "io.socket_read_ms"),
            Map.entry("jdk.SocketWrite", "io.socket_write_ms"),
            Map.entry("jdk.JavaMonitorEnter", "lock.enter_ms"),
            Map.entry("jdk.JavaMonitorWait", "lock.wait_ms"),
            Map.entry("jdk.ThreadPark", "lock.park_ms"),
            Map.entry("profiler.NativeLock", "lock.native_ms"),
            Map.entry("jdk.SafepointBegin", "safepoint.ms"),
            Map.entry("jdk.ExecuteVMOperation", "vm_op.ms"),
            Map.entry("jdk.Compilation", "compile.ms"),
            Map.entry("jdk.Deoptimization", "deopt.ms"));

    private MetricRegistry() {}

    public static Metric get(String id) {
        for (Metric metric : ALL) {
            if (metric.id().equals(id)) {
                return metric;
            }
        }
        return null;
    }

    public static String durationMetric(String eventType) {
        return DURATION_METRIC.get(eventType);
    }

    private static Metric metric(String id, String unit, String kind, String nativePeriod, int minJdk, String... sourceEvents) {
        return new Metric(id, unit, List.of(sourceEvents), kind, nativePeriod, minJdk);
    }

    public record Metric(String id, String unit, List<String> sourceEvents, String kind, String nativePeriod, int minJdk) {}
}

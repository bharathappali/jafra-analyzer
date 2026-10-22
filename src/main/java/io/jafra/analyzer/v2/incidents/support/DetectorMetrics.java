package io.jafra.analyzer.v2.incidents.support;

/** Catalogue metric names detectors read. No other metric literals belong in detector classes. */
public final class DetectorMetrics {
    public static final String CPU_USED_CORES = "cpu.used.cores";
    public static final String CPU_LIMIT_CORES = "cpu.limit.cores";
    public static final String CPU_CONTAINER_PCT_LIMIT = "cpu.container.pct_limit";
    public static final String CPU_THROTTLED_PCT = "cpu.throttled.pct";
    public static final String CPU_THROTTLED_S_PER_S = "cpu.throttled.s_per_s";
    public static final String CPU_THREAD_PCT = "cpu.thread.pct";
    public static final String CPU_SAMPLES_COUNT = "cpu.samples.count";
    public static final String HEAP_MAX_BYTES = "heap.max.bytes";
    public static final String HEAP_COMMITTED_BYTES = "heap.committed.bytes";
    public static final String HEAP_AFTER_GC_BYTES = "heap.after_gc.bytes";
    public static final String HEAP_OCCUPANCY_PCT = "heap.occupancy.pct";
    public static final String ALLOC_RATE = "alloc.rate.bytes_per_s";
    public static final String ALLOC_SAMPLED = "alloc.sampled.bytes";
    public static final String GC_PAUSE_TIME_PCT = "gc.pause.time_pct";
    public static final String GC_CPU_PCT = "gc.cpu.pct";
    public static final String GC_COUNT_PER_S = "gc.count.per_s";
    public static final String GC_FULL_COUNT = "gc.full.count";
    public static final String GC_PAUSE_US = "gc.pause.us";
    public static final String GC_ZGC_STALL_US = "gc.zgc.stall.us";
    public static final String METASPACE_USED = "metaspace.used.bytes";
    public static final String CLASSES_LOADED = "classes.loaded.per_s";
    public static final String CLASSES_UNLOADED = "classes.unloaded.per_s";
    public static final String CLASSES_LOADER_CLASSES = "classes.loader.classes.count";
    public static final String CLASSES_LOADER_CHUNK = "classes.loader.chunk.bytes";
    public static final String NMT_COMMITTED = "nmt.committed.bytes";
    public static final String NMT_CATEGORY = "nmt.category.committed.bytes";
    public static final String NMT_DIRECT_USED = "nmt.direct.used.bytes";
    public static final String NMT_DIRECT_BUFFERS = "nmt.direct.buffers.count";
    public static final String CONTAINER_USAGE = "container.usage.bytes";
    public static final String CONTAINER_LIMIT = "container.limit.bytes";
    public static final String CONTAINER_USAGE_PCT = "container.usage.pct_limit";
    public static final String CONTAINER_RSS = "container.rss.bytes";
    public static final String CONTAINER_MEM_FAIL = "container.mem_fail.count";
    public static final String THREADS_LIVE = "threads.live.count";
    public static final String THREADS_STARTED = "threads.started.per_s";
    public static final String THREADS_CREATED = "threads.created.count";
    public static final String THREADS_STATE = "threads.state.samples";
    public static final String LOCKS_WAIT = "locks.wait.ms_per_s";
    public static final String VT_PINNED_US = "vt.pinned.us";
    public static final String VT_PINNED_PER_S = "vt.pinned.per_s";
    public static final String VT_PINNED_MS = "vt.pinned.ms_per_s";
    public static final String VT_SUBMIT_FAILED = "vt.submit_failed.count";
    public static final String SAFEPOINT_TTSP = "safepoint.ttsp.us";
    public static final String SAFEPOINT_TIME_PCT = "safepoint.time_pct";
    public static final String SAFEPOINT_DURATION = "safepoint.duration.us";
    public static final String JIT_DEOPTS = "jit.deopts.per_min";
    public static final String JIT_QUEUE = "jit.queue.size";
    public static final String SOCKET_READ = "socket.read.us";
    public static final String SOCKET_WRITE = "socket.write.us";
    public static final String SOCKET_READ_TIME = "socket.read_time.ms_per_s";
    public static final String SOCKET_TLS = "socket.tls.handshakes.per_s";
    public static final String FILE_IO = "file.io.us";
    public static final String EXCEPTIONS_THROWN = "exceptions.thrown.per_s";
    public static final String EXCEPTIONS_ERRORS = "exceptions.errors.count";
    public static final String ENDPOINT_CALLS = "endpoint.calls.per_s";
    public static final String ENDPOINT_LATENCY = "endpoint.latency.us";
    public static final String ENDPOINT_SLOW_US = "endpoint.slow.us";
    public static final String ENDPOINT_SLOW_COUNT = "endpoint.slow.count";
    public static final String ENDPOINT_SLOW_PCT = "endpoint.slow.pct";

    private DetectorMetrics() {}
}

package io.jafra.analyzer.v2.incidents.support;

/** Internal detector inputs. Names start with {@code internal.} and are not catalogue metrics. */
public final class InternalInputs {
    public static final String GC_PHASE_L1 = "internal.gc.phase_l1";
    public static final String CHUNK_EVENT_TYPES = "internal.chunk.event_types";
    public static final String DIRECT_MAX_BYTES = "internal.direct.max.bytes";
    public static final String LOADER_COUNT = "internal.loader.count";
    public static final String LOADER_TYPE_CLASSES = "internal.loader.type.classes";
    public static final String LOADER_TYPE_CHUNK_BYTES = "internal.loader.type.chunk.bytes";
    public static final String LOADER_CLASS_DELTA = "internal.loader.class_delta";
    public static final String ZGC_STALL = "internal.zgc.stall";
    public static final String REDEFINE = "internal.redefine";
    public static final String LOCK_WAIT = "internal.lock.wait_ms_per_s";
    public static final String METHOD_TIMING_RAW = "internal.methodtiming.raw";
    public static final String ENDPOINT_WINDOW = "internal.endpoint.window";

    private InternalInputs() {}
}

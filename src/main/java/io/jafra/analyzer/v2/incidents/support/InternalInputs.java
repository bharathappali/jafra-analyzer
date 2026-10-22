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
    public static final String METHOD_INVOCATIONS = "internal.methodtiming.invocations";
    public static final String METHOD_AVERAGE = "internal.methodtiming.average";
    public static final String METHOD_MAXIMUM = "internal.methodtiming.maximum";
    public static final String METHOD_MINIMUM = "internal.methodtiming.minimum";
    public static final String ENDPOINT_CALLS = "internal.endpoint.calls";
    public static final String ENDPOINT_AVG_US = "internal.endpoint.avg_us";
    public static final String ENDPOINT_MAX_US = "internal.endpoint.max_us";

    private InternalInputs() {}
}

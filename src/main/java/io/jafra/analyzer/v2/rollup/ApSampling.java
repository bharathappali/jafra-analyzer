package io.jafra.analyzer.v2.rollup;

/** Async-profiler sample weights. The allocation field is unverified. */
public final class ApSampling {
    /** UNVERIFIED: which field async-profiler 4.5 sets as the allocation sample weight. */
    public static final String ALLOC_WEIGHT_FIELD = "allocationSize";

    private ApSampling() {}
}

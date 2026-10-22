package io.jafra.analyzer.v2.rollup;

/**
 * Draft Horvitz–Thompson correction for the async-profiler shared-bucket lock sampler.
 * UNVERIFIED. {@code W ≈ Σ max(d, I) / Δt} and {@code n ≈ Σ max(1, I/d)}.
 */
public final class LockCorrection {
    private LockCorrection() {}

    public static double correctedWaitMs(double durationMs, double intervalMs) {
        return Math.max(durationMs, intervalMs);
    }

    public static double correctedCount(double durationMs, double intervalMs) {
        if (durationMs <= 0d) {
            return 1d;
        }
        return Math.max(1d, intervalMs / durationMs);
    }
}

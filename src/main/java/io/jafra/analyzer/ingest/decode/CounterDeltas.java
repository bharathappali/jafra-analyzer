package io.jafra.analyzer.ingest.decode;

/** Positive differences of a cumulative counter. A drop is skipped so a restart cannot go negative. */
public final class CounterDeltas {
    private CounterDeltas() {}

    public static Double delta(Double previous, double current) {
        if (previous == null || current < previous) {
            return null;
        }
        return current - previous;
    }

    public static Double secondsBetween(Long previousNs, long currentNs) {
        if (previousNs == null || currentNs < previousNs) {
            return null;
        }
        return (currentNs - previousNs) / 1_000_000_000.0;
    }
}

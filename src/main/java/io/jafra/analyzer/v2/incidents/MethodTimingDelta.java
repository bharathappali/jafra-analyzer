package io.jafra.analyzer.v2.incidents;

import java.util.List;

/**
 * The single MethodTiming switch. AUTO stays on the cumulative counter until invocations rise at
 * least once and never fall. Step 10 finishes the endpoint detectors that consume the deltas.
 * UNVERIFIED which JDK 25 recordings are cumulative versus per period.
 */
public final class MethodTimingDelta {
    public enum Mode {
        AUTO,
        CUMULATIVE,
        PER_PERIOD
    }

    private static volatile Mode mode = Mode.AUTO;

    private MethodTimingDelta() {}

    public static Mode mode() {
        return mode;
    }

    public static void setMode(Mode value) {
        mode = value == null ? Mode.AUTO : value;
    }

    /** Public endpoint.calls.per_s stays a cumulative counter unless the mode is PER_PERIOD. */
    public static boolean cumulativeCounter() {
        return mode != Mode.PER_PERIOD;
    }

    /**
     * AUTO: never decreased and rose at least once means CUMULATIVE. Anything else, including a
     * decrease after a cumulative inference, is PER_PERIOD for that window (a reset).
     */
    public static Mode infer(List<Double> invocations) {
        if (mode == Mode.CUMULATIVE || mode == Mode.PER_PERIOD) {
            return mode;
        }
        if (invocations == null || invocations.size() < 2) {
            return Mode.PER_PERIOD;
        }
        boolean rose = false;
        for (int i = 1; i < invocations.size(); i++) {
            double previous = invocations.get(i - 1);
            double current = invocations.get(i);
            if (current < previous) {
                return Mode.PER_PERIOD;
            }
            if (current > previous) {
                rose = true;
            }
        }
        return rose ? Mode.CUMULATIVE : Mode.PER_PERIOD;
    }

    public record Delta(double calls, Double avgUs, Double maxUs, boolean maxKnown, Mode inferred) {}

    /**
     * CUMULATIVE: calls are the invocation delta. Average is the delta of (avg * invocations).
     * Max is known only when maximum increased. PER_PERIOD returns the readings as-is.
     */
    public static Delta delta(Mode inferred, double previousInvocations, double invocations, Double previousAvg,
            Double average, Double previousMax, Double maximum) {
        if (inferred != Mode.CUMULATIVE) {
            return new Delta(invocations, average, maximum, maximum != null, inferred);
        }
        double calls = invocations - previousInvocations;
        Double avg = null;
        if (calls > 0d && average != null && previousAvg != null) {
            avg = (average * invocations - previousAvg * previousInvocations) / calls;
        }
        boolean maxKnown = maximum != null && previousMax != null && maximum > previousMax;
        return new Delta(calls, avg, maxKnown ? maximum : null, maxKnown, inferred);
    }
}

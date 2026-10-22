package io.jafra.analyzer.v2.incidents.support;

/** z = (x − median) / (1.4826 · MAD) on raw values. */
public final class RobustZ {
    public static final double SCALE = 1.4826;
    public static final int WARMUP = 12;
    public static final int PROVEN = 60;

    private RobustZ() {}

    public static Double z(double x, double median, double mad, int n) {
        if (n < WARMUP) {
            return null;
        }
        if (mad == 0d) {
            if (x > median) {
                return Double.POSITIVE_INFINITY;
            }
            if (x < median) {
                return Double.NEGATIVE_INFINITY;
            }
            return 0d;
        }
        return (x - median) / (SCALE * mad);
    }

    public static boolean unproven(int n) {
        return n < PROVEN;
    }
}

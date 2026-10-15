package io.jafra.analyzer.api.v2;

import java.math.MathContext;
import java.math.RoundingMode;

/** Three significant figures for compact v2 responses. */
public final class CompactNumbers {
    private static final MathContext THREE = new MathContext(3, RoundingMode.HALF_UP);

    private CompactNumbers() {}

    public static Double round(double value, boolean compact) {
        if (!Double.isFinite(value)) {
            return null;
        }
        if (!compact) {
            return value;
        }
        if (value == 0.0) {
            return 0.0;
        }
        return new java.math.BigDecimal(value, MathContext.DECIMAL64).round(THREE).doubleValue();
    }
}

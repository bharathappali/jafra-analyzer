package io.jafra.analyzer.v2.rollup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Log buckets, ratio 1.02, from 1 µs to 1 h. Empty buckets are not stored. */
public final class LogHistogram {
    public static final double RATIO = 1.02;
    public static final double LN_RATIO = Math.log(RATIO);
    /** 1 hour in microseconds. */
    public static final double ONE_HOUR_US = 3_600_000_000d;
    public static final int UNDERFLOW = -1;
    public static final int OVERFLOW = 1111;
    public static final int MAX_FINITE = 1110;

    private LogHistogram() {}

    /**
     * Bucket {@code floor(ln(v)/ln(1.02))} for 1 µs ≤ v &lt; 1 h, clamped to 0..1110.
     * Values below 1 µs are {@link #UNDERFLOW}. Values at or above 1 h are {@link #OVERFLOW}.
     */
    public static int bucket(double microseconds) {
        if (microseconds < 1d) {
            return UNDERFLOW;
        }
        if (microseconds >= ONE_HOUR_US) {
            return OVERFLOW;
        }
        int index = (int) Math.floor(Math.log(microseconds) / LN_RATIO);
        if (index < 0) {
            return 0;
        }
        if (index > MAX_FINITE) {
            return MAX_FINITE;
        }
        return index;
    }

    /** Representative value of a finite bucket. Underflow and overflow use the series extremes. */
    public static double representative(int bucket, double seriesMin, double seriesMax) {
        if (bucket == UNDERFLOW) {
            return seriesMin;
        }
        if (bucket == OVERFLOW) {
            return seriesMax;
        }
        return Math.pow(RATIO, bucket + 0.5);
    }

    public static Map<Integer, Long> buckets(double[] microseconds) {
        Map<Integer, Long> counts = new TreeMap<>();
        if (microseconds == null) {
            return counts;
        }
        for (double value : microseconds) {
            counts.merge(bucket(value), 1L, Long::sum);
        }
        counts.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() <= 0);
        return counts;
    }

    public static Map<Integer, Long> merge(Iterable<Map<Integer, Long>> windows) {
        Map<Integer, Long> counts = new TreeMap<>();
        for (Map<Integer, Long> window : windows) {
            if (window == null) {
                continue;
            }
            for (Map.Entry<Integer, Long> entry : window.entrySet()) {
                if (entry.getValue() != null && entry.getValue() > 0) {
                    counts.merge(entry.getKey(), entry.getValue(), Long::sum);
                }
            }
        }
        return counts;
    }

    /** Rank {@code ceil(q·N)} over cumulative bucket counts, buckets in ascending order. */
    public static double percentile(Map<Integer, Long> buckets, double q, double seriesMin, double seriesMax) {
        long total = 0;
        for (long count : buckets.values()) {
            total += count;
        }
        if (total <= 0) {
            return Double.NaN;
        }
        long rank = (long) Math.ceil(q * total);
        if (rank < 1) {
            rank = 1;
        }
        List<Integer> keys = new ArrayList<>(buckets.keySet());
        keys.sort(Integer::compareTo);
        long cumulative = 0;
        for (int key : keys) {
            cumulative += buckets.get(key);
            if (cumulative >= rank) {
                return representative(key, seriesMin, seriesMax);
            }
        }
        return representative(keys.get(keys.size() - 1), seriesMin, seriesMax);
    }
}

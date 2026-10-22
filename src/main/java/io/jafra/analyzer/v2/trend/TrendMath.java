package io.jafra.analyzer.v2.trend;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.jafra.analyzer.v2.api.V2Exception;

/**
 * Theil–Sen slope, Mann–Kendall with tie correction, and Hamed–Rao variance.
 * Slope is per hour. Detector constants here are proposals, pending KUSMA.
 */
public final class TrendMath {
    static final int MIN_POINTS = 30;
    static final long MIN_SPAN_MS = 300_000L;
    static final double ALPHA = 0.01;
    private static final double PLATEAU_P = 0.05;

    private TrendMath() {}

    public static Result assess(long[] timesMs, double[] values, Double limit) {
        int n = values.length;
        if (timesMs.length != n) {
            throw new IllegalArgumentException("times and values differ");
        }
        long span = n == 0 ? 0 : timesMs[n - 1] - timesMs[0];
        if (n < MIN_POINTS || span < MIN_SPAN_MS) {
            throw V2Exception.notEnough(n);
        }
        double[] hours = new double[n];
        double origin = timesMs[0] / 3_600_000d;
        for (int i = 0; i < n; i++) {
            hours[i] = timesMs[i] / 3_600_000d - origin;
        }
        double[] slopes = pairSlopes(hours, values);
        double b = median(slopes);
        double[] intercepts = new double[n];
        for (int i = 0; i < n; i++) {
            intercepts[i] = values[i] - b * hours[i];
        }
        double a = median(intercepts);
        Kendall full = kendall(values);
        double[] residual = new double[n];
        for (int i = 0; i < n; i++) {
            residual[i] = values[i] - (a + b * hours[i]);
        }
        double factor = hamedRao(rank(residual), n);
        double varStar = full.variance * Math.max(1d, factor);
        double rawP = pValue(full.s, full.variance);
        double p = pValue(full.s, varStar);
        boolean significant = p < ALPHA;
        double[] ordered = slopes.clone();
        Arrays.sort(ordered);
        int count = ordered.length;
        double c = 1.96 * Math.sqrt(Math.max(varStar, 0d));
        int lo = clamp((int) Math.floor((count - c) / 2d), 1, count);
        int hi = clamp((int) Math.ceil((count + c) / 2d + 1d), 1, count);
        double slopeLow = ordered[lo - 1];
        double slopeHigh = ordered[hi - 1];
        int recentFrom = recentStart(hours);
        Kendall recent = kendall(Arrays.copyOfRange(values, recentFrom, n));
        double bRecent = median(pairSlopes(Arrays.copyOfRange(hours, recentFrom, n), Arrays.copyOfRange(values, recentFrom, n)));
        double pRecent = pValue(recent.s, recent.variance);
        boolean plateau = significant && b > 0d && bRecent <= 0.25d * b && pRecent >= PLATEAU_P;
        boolean staircase = plateau && staircase(hours, values);
        String shape;
        if (staircase) {
            shape = "staircase";
        } else if (plateau) {
            shape = "plateau";
        } else if (significant && b > 0d) {
            shape = "rising";
        } else if (significant && b < 0d) {
            shape = "falling";
        } else {
            shape = "flat";
        }
        TimeToLimit time = null;
        if (significant && b > 0d && !plateau && limit != null) {
            double yNow = a + b * hours[n - 1];
            double gap = limit - yNow;
            Double low = slopeHigh <= 0d ? null : gap / slopeHigh * 60d;
            Double high = slopeLow <= 0d ? null : gap / slopeLow * 60d;
            time = new TimeToLimit(low, gap / b * 60d, high);
        }
        return new Result(b, slopeLow, slopeHigh, p, rawP, significant, shape, limit, time, n);
    }

    static double[] pairSlopes(double[] t, double[] y) {
        int n = y.length;
        double[] slopes = new double[n * (n - 1) / 2];
        int at = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dt = t[j] - t[i];
                slopes[at++] = dt == 0d ? 0d : (y[j] - y[i]) / dt;
            }
        }
        return slopes;
    }

    static double median(double[] values) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        int n = copy.length;
        if (n == 0) {
            return 0d;
        }
        if (n % 2 == 1) {
            return copy[n / 2];
        }
        return (copy[n / 2 - 1] + copy[n / 2]) / 2d;
    }

    static Kendall kendall(double[] y) {
        int n = y.length;
        long s = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                s += Math.signum(y[j] - y[i]);
            }
        }
        int[] ties = tieSizes(y);
        double correction = 0;
        for (int size : ties) {
            correction += (double) size * (size - 1) * (2L * size + 5);
        }
        double variance = (n * (n - 1d) * (2d * n + 5) - correction) / 18d;
        return new Kendall(s, Math.max(0d, variance));
    }

    /** Variance inflation n/n*. Ranks are 1-based average ranks. */
    static double hamedRao(double[] ranks, int n) {
        if (n < 4) {
            return 1d;
        }
        double mean = 0;
        for (double rank : ranks) {
            mean += rank;
        }
        mean /= n;
        double denom = 0;
        for (double rank : ranks) {
            double d = rank - mean;
            denom += d * d;
        }
        if (denom == 0d) {
            return 1d;
        }
        double sum = 0;
        double gate = 1.96 / Math.sqrt(n);
        int maxLag = Math.min(30, n - 1);
        for (int lag = 1; lag <= maxLag; lag++) {
            double num = 0;
            for (int i = 0; i < n - lag; i++) {
                num += (ranks[i] - mean) * (ranks[i + lag] - mean);
            }
            double rho = num / denom;
            if (Math.abs(rho) > gate) {
                int nk = n - lag;
                sum += (double) nk * (nk - 1) * (nk - 2) * rho;
            }
        }
        return 1d + 2d / (n * (n - 1d) * (n - 2d)) * sum;
    }

    static double pValue(long s, double variance) {
        if (variance <= 0d || s == 0) {
            return 1d;
        }
        double z = (s - Math.signum(s)) / Math.sqrt(variance);
        return 2d * (1d - normalCdf(Math.abs(z)));
    }

    static double normalCdf(double z) {
        double t = 1d / (1d + 0.2316419 * Math.abs(z));
        double density = 0.3989422804014327 * Math.exp(-0.5 * z * z);
        double tail = density * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        return z >= 0d ? 1d - tail : tail;
    }

    private static int recentStart(double[] hours) {
        double span = hours[hours.length - 1] - hours[0];
        double cutoff = hours[hours.length - 1] - 0.25 * span;
        int start = hours.length - 1;
        while (start > 0 && hours[start - 1] >= cutoff) {
            start--;
        }
        if (hours.length - start < 10) {
            return Math.max(0, hours.length - 10);
        }
        return start;
    }

    private static boolean staircase(double[] hours, double[] values) {
        int n = values.length;
        double start = hours[0];
        double span = hours[n - 1] - start;
        if (span <= 0d) {
            return false;
        }
        double[] medians = new double[6];
        double[] residuals = new double[n];
        int filled = 0;
        for (int segment = 0; segment < 6; segment++) {
            double lo = start + span * segment / 6d;
            double hi = start + span * (segment + 1) / 6d;
            List<Double> bucket = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                boolean inside = segment == 5 ? hours[i] >= lo && hours[i] <= hi : hours[i] >= lo && hours[i] < hi;
                if (inside) {
                    bucket.add(values[i]);
                }
            }
            if (bucket.isEmpty()) {
                return false;
            }
            double[] raw = new double[bucket.size()];
            for (int i = 0; i < bucket.size(); i++) {
                raw[i] = bucket.get(i);
            }
            medians[segment] = median(raw);
            filled++;
        }
        if (filled < 6) {
            return false;
        }
        int at = 0;
        for (int segment = 0; segment < 6; segment++) {
            double lo = start + span * segment / 6d;
            double hi = start + span * (segment + 1) / 6d;
            for (int i = 0; i < n; i++) {
                boolean inside = segment == 5 ? hours[i] >= lo && hours[i] <= hi : hours[i] >= lo && hours[i] < hi;
                if (inside) {
                    residuals[at++] = values[i] - medians[segment];
                }
            }
        }
        double[] used = Arrays.copyOf(residuals, at);
        double sigma = 1.4826 * median(abs(used));
        boolean[] rises = new boolean[5];
        for (int i = 1; i < 6; i++) {
            rises[i - 1] = medians[i] - medians[i - 1] > 3d * sigma;
        }
        for (int i = 0; i < rises.length; i++) {
            if (!rises[i]) {
                continue;
            }
            for (int j = i + 2; j < rises.length; j++) {
                if (!rises[j]) {
                    continue;
                }
                for (int k = i + 1; k < j; k++) {
                    if (!rises[k]) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static double[] abs(double[] values) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = Math.abs(values[i]);
        }
        return out;
    }

    private static double[] rank(double[] values) {
        int n = values.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Double.compare(values[a], values[b]));
        double[] ranks = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && values[order[j + 1]] == values[order[i]]) {
                j++;
            }
            double average = (i + 1 + j + 1) / 2d;
            for (int k = i; k <= j; k++) {
                ranks[order[k]] = average;
            }
            i = j + 1;
        }
        return ranks;
    }

    private static int[] tieSizes(double[] values) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        List<Integer> sizes = new ArrayList<>();
        int i = 0;
        while (i < copy.length) {
            int j = i;
            while (j + 1 < copy.length && copy[j + 1] == copy[i]) {
                j++;
            }
            if (j > i) {
                sizes.add(j - i + 1);
            }
            i = j + 1;
        }
        int[] out = new int[sizes.size()];
        for (int k = 0; k < sizes.size(); k++) {
            out[k] = sizes.get(k);
        }
        return out;
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    public record Kendall(long s, double variance) {}

    public record TimeToLimit(Double low, double mid, Double high) {}

    public record Result(
            double slopePerHour,
            double slopeLow,
            double slopeHigh,
            double mkP,
            double rawMkP,
            boolean significant,
            String shape,
            Double limit,
            TimeToLimit timeToLimitMin,
            int n) {}
}

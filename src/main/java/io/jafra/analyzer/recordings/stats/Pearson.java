package io.jafra.analyzer.recordings.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lagged Pearson correlation over aligned buckets. A bucket pairs only when both sides are non-null.
 * The reported lag is the shift in {@code -3..+3} with the largest absolute r. Equal |r| keeps the lag
 * closest to zero. {@code n} is the number
 * of pairs at that lag. Fewer than 20 pairs is {@code insufficient_data}.
 */
public final class Pearson {
    public static final int MIN_PAIRS = 20;
    public static final int MAX_LAG = 3;

    private Pearson() {}

    public static Map<String, Object> bestLag(List<Double> left, List<Double> right) {
        int limit = Math.min(left.size(), right.size());
        int maxN = 0;
        boolean found = false;
        int bestLag = 0;
        double bestR = 0.0;
        double bestAbs = -1.0;
        int bestN = 0;
        for (int lag = -MAX_LAG; lag <= MAX_LAG; lag++) {
            List<Double> xs = new ArrayList<>();
            List<Double> ys = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                int j = i + lag;
                if (j < 0 || j >= limit) {
                    continue;
                }
                Double x = left.get(i);
                Double y = right.get(j);
                if (x == null || y == null || x.isNaN() || y.isNaN()) {
                    continue;
                }
                xs.add(x);
                ys.add(y);
            }
            maxN = Math.max(maxN, xs.size());
            if (xs.size() < MIN_PAIRS) {
                continue;
            }
            double r = coefficient(xs, ys);
            double abs = Math.abs(r);
            boolean stronger = abs > bestAbs + 1e-9;
            boolean closerTie = abs + 1e-9 >= bestAbs && Math.abs(lag) < Math.abs(bestLag);
            if (!found || stronger || closerTie) {
                found = true;
                bestAbs = abs;
                bestR = r;
                bestLag = lag;
                bestN = xs.size();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (!found) {
            result.put("status", "insufficient_data");
            result.put("n", maxN);
            return result;
        }
        result.put("status", "ok");
        result.put("r", bestR);
        result.put("lagBuckets", bestLag);
        result.put("n", bestN);
        return result;
    }

    static double coefficient(List<Double> xs, List<Double> ys) {
        int n = xs.size();
        double meanX = 0.0;
        double meanY = 0.0;
        for (int i = 0; i < n; i++) {
            meanX += xs.get(i);
            meanY += ys.get(i);
        }
        meanX /= n;
        meanY /= n;
        double cov = 0.0;
        double varX = 0.0;
        double varY = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = xs.get(i) - meanX;
            double dy = ys.get(i) - meanY;
            cov += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }
        double denom = Math.sqrt(varX * varY);
        if (denom == 0.0) {
            return 0.0;
        }
        return cov / denom;
    }
}

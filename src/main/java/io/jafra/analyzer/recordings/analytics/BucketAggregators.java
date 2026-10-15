package io.jafra.analyzer.recordings.analytics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.jafra.analyzer.recordings.TimeBucketGrid;

/** Per-bucket accumulators for gauge, counter-delta, duration, and sample counts. */
public final class BucketAggregators {
    private BucketAggregators() {}

    public static double[] newGaugeMeans(int buckets) {
        return new double[buckets];
    }

    public static int[] newGaugeCounts(int buckets) {
        return new int[buckets];
    }

    public static double[] newGaugeLast(int buckets) {
        double[] last = new double[buckets];
        Arrays.fill(last, Double.NaN);
        return last;
    }

    public static void addGauge(TimeBucketGrid grid, int[] counts, double[] sums, double[] last, long timeNs, double value) {
        int idx = grid.indexFor(timeNs);
        if (idx < 0) {
            return;
        }
        counts[idx]++;
        sums[idx] += value;
        last[idx] = value;
    }

    public static List<AnalyticsModels.SeriesPoint> gaugeSeries(
            TimeBucketGrid grid, int[] counts, double[] sums, double[] last) {
        List<AnalyticsModels.SeriesPoint> points = new ArrayList<>(grid.bucketCount());
        for (TimeBucketGrid.BucketPoint bucket : grid.isoStarts()) {
            int i = bucket.index();
            Number mean = counts[i] > 0 ? sums[i] / counts[i] : null;
            Number value = Double.isNaN(last[i]) ? null : last[i];
            points.add(new AnalyticsModels.SeriesPoint(bucket.at(), value, mean, counts[i], null));
        }
        return points;
    }

    public static long[] newCounterLast(int buckets) {
        long[] last = new long[buckets];
        Arrays.fill(last, Long.MIN_VALUE);
        return last;
    }

    public static void addCounterSample(TimeBucketGrid grid, long[] lastValue, long timeNs, long cumulative) {
        int idx = grid.indexFor(timeNs);
        if (idx < 0) {
            return;
        }
        lastValue[idx] = cumulative;
    }

    public static List<AnalyticsModels.SeriesPoint> counterDeltaSeries(TimeBucketGrid grid, long[] lastValue) {
        List<AnalyticsModels.SeriesPoint> points = new ArrayList<>(grid.bucketCount());
        long prev = 0L;
        boolean seen = false;
        for (TimeBucketGrid.BucketPoint bucket : grid.isoStarts()) {
            int i = bucket.index();
            long val = lastValue[i];
            Number delta = null;
            if (val != Long.MIN_VALUE) {
                if (seen) {
                    delta = val - prev;
                }
                prev = val;
                seen = true;
            }
            points.add(new AnalyticsModels.SeriesPoint(bucket.at(), delta, null, null, null));
        }
        return points;
    }

    public static int[] newCountBuckets(int buckets) {
        return new int[buckets];
    }

    public static double[] newSumBuckets(int buckets) {
        return new double[buckets];
    }

    public static double[] newMaxBuckets(int buckets) {
        double[] max = new double[buckets];
        Arrays.fill(max, Double.NaN);
        return max;
    }

    public static void addDurationMs(
            TimeBucketGrid grid, int[] counts, double[] sumMs, double[] maxMs, long startNs, double durationMs) {
        if (durationMs <= 0.0 || grid.bucketCount() == 0) {
            return;
        }
        long durationNs = Math.round(durationMs * 1_000_000.0);
        if (durationNs <= 0L) {
            return;
        }
        long endNs = startNs + durationNs;
        long windowFrom = grid.fromEpochNs();
        long windowTo = grid.toEpochNs();
        long overlapStart = Math.max(startNs, windowFrom);
        long overlapEnd = Math.min(endNs, windowTo);
        if (overlapEnd <= overlapStart) {
            return;
        }
        int first = grid.indexFor(overlapStart);
        int last = grid.indexFor(overlapEnd - 1L);
        if (first < 0 || last < 0) {
            return;
        }
        for (int idx = first; idx <= last; idx++) {
            long bucketStart = grid.bucketStartNs(idx);
            long bucketEnd = grid.bucketEndNs(idx);
            long lo = Math.max(startNs, bucketStart);
            long hi = Math.min(endNs, bucketEnd);
            if (hi <= lo) {
                continue;
            }
            double portionMs = (hi - lo) / 1_000_000.0;
            sumMs[idx] += portionMs;
            if (Double.isNaN(maxMs[idx]) || portionMs > maxMs[idx]) {
                maxMs[idx] = portionMs;
            }
        }
        int countIdx;
        if (startNs >= windowFrom && startNs < windowTo) {
            countIdx = grid.indexFor(startNs);
        } else if (startNs < windowFrom) {
            countIdx = grid.indexFor(windowFrom);
        } else {
            countIdx = -1;
        }
        if (countIdx >= 0) {
            counts[countIdx]++;
        }
    }

    public static List<AnalyticsModels.SeriesPoint> durationSumSeries(
            TimeBucketGrid grid, int[] counts, double[] sumMs, double[] maxMs) {
        List<AnalyticsModels.SeriesPoint> points = new ArrayList<>(grid.bucketCount());
        for (TimeBucketGrid.BucketPoint bucket : grid.isoStarts()) {
            int i = bucket.index();
            Number sum = counts[i] > 0 ? sumMs[i] : null;
            Number max = Double.isNaN(maxMs[i]) ? null : maxMs[i];
            points.add(new AnalyticsModels.SeriesPoint(bucket.at(), sum, null, counts[i], max));
        }
        return points;
    }

    public static void addPointSum(TimeBucketGrid grid, int[] counts, double[] sums, long timeNs, double value) {
        int idx = grid.indexFor(timeNs);
        if (idx >= 0) {
            counts[idx]++;
            sums[idx] += value;
        }
    }

    public static List<AnalyticsModels.SeriesPoint> nullableCountSeries(TimeBucketGrid grid, int[] counts) {
        List<AnalyticsModels.SeriesPoint> points = new ArrayList<>(grid.bucketCount());
        for (TimeBucketGrid.BucketPoint bucket : grid.isoStarts()) {
            int i = bucket.index();
            Number value = counts[i] > 0 ? counts[i] : null;
            points.add(new AnalyticsModels.SeriesPoint(bucket.at(), value, null, counts[i], null));
        }
        return points;
    }

    public static void incrementCount(TimeBucketGrid grid, int[] counts, long timeNs) {
        int idx = grid.indexFor(timeNs);
        if (idx >= 0) {
            counts[idx]++;
        }
    }

    public static List<AnalyticsModels.SeriesPoint> countSeries(TimeBucketGrid grid, int[] counts, double scale) {
        List<AnalyticsModels.SeriesPoint> points = new ArrayList<>(grid.bucketCount());
        double bucketSec = grid.bucketWidthNs() / 1_000_000_000.0;
        for (TimeBucketGrid.BucketPoint bucket : grid.isoStarts()) {
            int i = bucket.index();
            Number rate = counts[i] > 0 && bucketSec > 0 ? counts[i] / bucketSec * scale : counts[i] == 0 ? 0 : null;
            points.add(new AnalyticsModels.SeriesPoint(bucket.at(), rate, null, counts[i], null));
        }
        return points;
    }
}

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
            TimeBucketGrid grid, int[] counts, double[] sumMs, double[] maxMs, long timeNs, double durationMs) {
        int idx = grid.indexFor(timeNs);
        if (idx < 0) {
            return;
        }
        counts[idx]++;
        sumMs[idx] += durationMs;
        if (Double.isNaN(maxMs[idx]) || durationMs > maxMs[idx]) {
            maxMs[idx] = durationMs;
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

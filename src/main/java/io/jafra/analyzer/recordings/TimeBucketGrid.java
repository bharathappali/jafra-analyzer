package io.jafra.analyzer.recordings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Uniform time buckets for analytics timeseries (LLM-sized, capped). */
public final class TimeBucketGrid {
    public static final int TARGET_BUCKETS = 72;
    public static final int MIN_BUCKETS = 60;
    public static final int MAX_BUCKETS = 120;
    public static final long MIN_BUCKET_SECONDS = 1L;
    public static final long MAX_BUCKET_SECONDS = 3600L;

    private final long fromEpochNs;
    private final long toEpochNs;
    private final long bucketWidthNs;
    private final int bucketCount;

    private TimeBucketGrid(long fromEpochNs, long toEpochNs, long bucketWidthNs, int bucketCount) {
        this.fromEpochNs = fromEpochNs;
        this.toEpochNs = toEpochNs;
        this.bucketWidthNs = bucketWidthNs;
        this.bucketCount = bucketCount;
    }

    public static TimeBucketGrid of(Instant from, Instant to, Long bucketSecondsOverride) {
        long fromNs = from.getEpochSecond() * 1_000_000_000L + from.getNano();
        long toNs = to.getEpochSecond() * 1_000_000_000L + to.getNano();
        if (toNs <= fromNs) {
            toNs = fromNs + 1_000_000_000L;
        }
        long durationNs = toNs - fromNs;
        long bucketSec;
        if (bucketSecondsOverride != null && bucketSecondsOverride > 0) {
            bucketSec = Math.clamp(bucketSecondsOverride, MIN_BUCKET_SECONDS, MAX_BUCKET_SECONDS);
        } else {
            bucketSec = Math.max(MIN_BUCKET_SECONDS, (durationNs / 1_000_000_000L + TARGET_BUCKETS - 1) / TARGET_BUCKETS);
            bucketSec = Math.min(bucketSec, MAX_BUCKET_SECONDS);
        }
        long widthNs = bucketSec * 1_000_000_000L;
        int count = (int) Math.min(MAX_BUCKETS, Math.max(1, (durationNs + widthNs - 1) / widthNs));
        return new TimeBucketGrid(fromNs, toNs, widthNs, count);
    }

    public long fromEpochNs() {
        return fromEpochNs;
    }

    public long toEpochNs() {
        return toEpochNs;
    }

    public long bucketWidthNs() {
        return bucketWidthNs;
    }

    public int bucketCount() {
        return bucketCount;
    }

    public long bucketStartNs(int index) {
        return fromEpochNs + index * bucketWidthNs;
    }

    public long bucketEndNs(int index) {
        return Math.min(fromEpochNs + (index + 1L) * bucketWidthNs, toEpochNs);
    }

    public int indexFor(long eventTimeNs) {
        if (eventTimeNs < fromEpochNs || eventTimeNs >= toEpochNs) {
            return -1;
        }
        int idx = (int) ((eventTimeNs - fromEpochNs) / bucketWidthNs);
        return idx >= bucketCount ? bucketCount - 1 : idx;
    }

    public double windowDurationSeconds() {
        return (toEpochNs - fromEpochNs) / 1_000_000_000.0;
    }

    public List<BucketPoint> isoStarts() {
        List<BucketPoint> points = new ArrayList<>(bucketCount);
        for (int i = 0; i < bucketCount; i++) {
            long start = bucketStartNs(i);
            points.add(new BucketPoint(i, Instant.ofEpochSecond(start / 1_000_000_000L, (int) (start % 1_000_000_000L))
                    .toString()));
        }
        return points;
    }

    public record BucketPoint(int index, String at) {}
}

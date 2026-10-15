package io.jafra.analyzer.recordings.stats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Nearest-rank percentiles.
 *
 * <p>For a non-empty ascending sample of size {@code n} and a quantile {@code q} in {@code (0, 1]},
 * the rank is {@code ceil(q * n)} and the value is the element at index {@code rank - 1}, clamped to
 * {@code [0, n - 1]}. Ties are not averaged: the value already stored at that index is returned. An
 * empty sample has no percentile. A single sample returns that value for every quantile.
 */
public final class Percentiles {
    private Percentiles() {}

    public static OptionalDouble nearestRank(List<? extends Number> sortedAscending, double quantile) {
        int n = sortedAscending.size();
        if (n == 0) {
            return OptionalDouble.empty();
        }
        if (quantile <= 0.0 || quantile > 1.0) {
            throw new IllegalArgumentException("quantile must be in (0, 1]");
        }
        int index = (int) Math.ceil(n * quantile) - 1;
        if (index < 0) {
            index = 0;
        } else if (index >= n) {
            index = n - 1;
        }
        return OptionalDouble.of(sortedAscending.get(index).doubleValue());
    }

    public static Optional<Summary> summarize(List<? extends Number> values) {
        if (values.isEmpty()) {
            return Optional.empty();
        }
        List<Double> sorted = new ArrayList<>(values.size());
        for (Number value : values) {
            sorted.add(value.doubleValue());
        }
        Collections.sort(sorted);
        return Optional.of(new Summary(
                nearestRank(sorted, 0.50).orElseThrow(),
                nearestRank(sorted, 0.90).orElseThrow(),
                nearestRank(sorted, 0.95).orElseThrow(),
                nearestRank(sorted, 0.99).orElseThrow(),
                sorted.get(sorted.size() - 1)));
    }

    public record Summary(double p50, double p90, double p95, double p99, double max) {}
}

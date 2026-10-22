package io.jafra.analyzer.v2.rollup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

class LogHistogramTest {
    @Test
    void bucketBounds() {
        assertEquals(LogHistogram.UNDERFLOW, LogHistogram.bucket(0.5));
        assertEquals(0, LogHistogram.bucket(1d));
        assertEquals(1, LogHistogram.bucket(1.02));
        assertTrue(LogHistogram.bucket(LogHistogram.ONE_HOUR_US - 1d) <= LogHistogram.MAX_FINITE);
        assertEquals(LogHistogram.OVERFLOW, LogHistogram.bucket(LogHistogram.ONE_HOUR_US));
        assertEquals(LogHistogram.OVERFLOW, LogHistogram.bucket(LogHistogram.ONE_HOUR_US + 10d));
        assertEquals(Math.pow(1.02, 0.5), LogHistogram.representative(0, 0d, 0d), 1e-9);
    }

    @Test
    void mergedWindowsStayWithinTwoPercent() throws Exception {
        assertPercentile(logNormal(100_000, Math.log(1_000), 0.5, 1));
        assertPercentile(bimodal(100_000, 2));
    }

    @Test
    void emptyBucketsAreNotStored() throws Exception {
        double[] values = new double[] {10d, 10d, 50d};
        Map<Integer, Long> counts = LogHistogram.buckets(values);
        assertFalse(counts.containsValue(0L));
        assertEquals(2, counts.size());
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            connection.createStatement().execute(
                    "CREATE TABLE hist (bucket SMALLINT, count BIGINT)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO hist (bucket, count) VALUES (?, ?)")) {
                for (Map.Entry<Integer, Long> entry : counts.entrySet()) {
                    insert.setInt(1, entry.getKey());
                    insert.setLong(2, entry.getValue());
                    insert.executeUpdate();
                }
            }
            try (ResultSet rows = connection.createStatement().executeQuery(
                    "SELECT bucket, SUM(count) FROM hist GROUP BY bucket")) {
                while (rows.next()) {
                    assertTrue(rows.getLong(2) > 0);
                }
            }
        }
    }

    private static void assertPercentile(double[] values) throws Exception {
        List<Map<Integer, Long>> windows = new ArrayList<>();
        int width = values.length / 12;
        for (int window = 0; window < 12; window++) {
            double[] slice = Arrays.copyOfRange(values, window * width, (window + 1) * width);
            windows.add(LogHistogram.buckets(slice));
        }
        Map<Integer, Long> merged = groupBy(windows);
        assertEquals(LogHistogram.merge(windows), merged);
        double[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        double min = sorted[0];
        double max = sorted[sorted.length - 1];
        for (double q : new double[] {0.50, 0.95, 0.99}) {
            double exact = sorted[(int) Math.ceil(q * sorted.length) - 1];
            double approx = LogHistogram.percentile(merged, q, min, max);
            double error = Math.abs(approx - exact) / exact;
            assertTrue(error < 0.02, "q=" + q + " exact=" + exact + " approx=" + approx + " error=" + error);
        }
    }

    private static Map<Integer, Long> groupBy(List<Map<Integer, Long>> windows) throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        Map<Integer, Long> merged = new HashMap<>();
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            connection.createStatement().execute(
                    "CREATE TABLE hist (bucket SMALLINT, count BIGINT)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO hist (bucket, count) VALUES (?, ?)")) {
                for (Map<Integer, Long> window : windows) {
                    for (Map.Entry<Integer, Long> entry : window.entrySet()) {
                        assertTrue(entry.getValue() > 0);
                        insert.setInt(1, entry.getKey());
                        insert.setLong(2, entry.getValue());
                        insert.addBatch();
                    }
                }
                insert.executeBatch();
            }
            try (ResultSet rows = connection.createStatement().executeQuery(
                    "SELECT bucket, SUM(count) AS count FROM hist GROUP BY bucket")) {
                while (rows.next()) {
                    merged.put(rows.getInt(1), rows.getLong(2));
                }
            }
        }
        return merged;
    }

    private static double[] logNormal(int n, double mu, double sigma, long seed) {
        Random random = new Random(seed);
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            values[i] = Math.exp(mu + sigma * random.nextGaussian());
        }
        return values;
    }

    private static double[] bimodal(int n, long seed) {
        Random random = new Random(seed);
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            double mu = i % 2 == 0 ? Math.log(100) : Math.log(100_000);
            values[i] = Math.exp(mu + 0.25 * random.nextGaussian());
        }
        return values;
    }
}

package io.jafra.analyzer.v2.query;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record QueryResponse(
        String metric,
        Double value,
        String unit,
        RangeWindow window,
        long n,
        List<String> flags,
        String stat,
        String stepUsed,
        List<QueryPoint> points,
        QuerySummary summary,
        List<BreakdownRow> rows,
        Double otherPct,
        Double total,
        List<KeyedSeries> series) {

    static QueryResponse scalar(
            String metric, double value, String unit, RangeWindow window, long n, List<String> flags, String stat) {
        return new QueryResponse(metric, value, unit, window, n, flags, stat, null, null, null, null, null, null, null);
    }

    static QueryResponse series(
            String metric,
            String unit,
            RangeWindow window,
            long n,
            List<String> flags,
            String stat,
            String stepUsed,
            List<QueryPoint> points,
            QuerySummary summary,
            String ignored) {
        return new QueryResponse(metric, null, unit, window, n, flags, stat, stepUsed, points, summary, null, null, null, null);
    }

    static QueryResponse breakdown(
            String metric,
            String unit,
            RangeWindow window,
            long n,
            List<String> flags,
            String stat,
            List<BreakdownRow> rows,
            double otherPct,
            double total) {
        return new QueryResponse(metric, null, unit, window, n, flags, stat, null, null, null, rows, otherPct, total, null);
    }

    static QueryResponse byStep(
            String metric,
            String unit,
            RangeWindow window,
            long n,
            List<String> flags,
            String stat,
            String stepUsed,
            List<KeyedSeries> series) {
        return new QueryResponse(metric, null, unit, window, n, flags, stat, stepUsed, null, null, null, null, null, series);
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record RangeWindow(String from, String to, String step) {}

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record QueryPoint(long t, double v) {}

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record QuerySummary(double first, double last, double min, String minAt, double max, String maxAt, double median) {}

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record BreakdownRow(String key, double value, double sharePct) {}

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record KeyedSeries(String key, List<QueryPoint> points) {}

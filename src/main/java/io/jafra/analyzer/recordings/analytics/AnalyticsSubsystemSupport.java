package io.jafra.analyzer.recordings.analytics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openjdk.jmc.common.item.IItem;
import java.time.Instant;

import io.jafra.analyzer.recordings.JfrAggregationService;
import io.jafra.analyzer.recordings.JfrAnalyticsSupport;
import io.jafra.analyzer.recordings.JfrItemAccess;
import io.jafra.analyzer.recordings.TimeBucketGrid;
import io.jafra.analyzer.signals.SignalRegistry;

abstract class AnalyticsSubsystemSupport implements SubsystemBuilder {
    protected static Map<String, Object> evidenceFromRanks(
            List<JfrAggregationService.RankedEntry> ranks, boolean stackIds) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (JfrAggregationService.RankedEntry rank : ranks) {
            items.add(stackRow(rank, stackIds));
        }
        evidence.put("top", items);
        return evidence;
    }

    protected static Map<String, Object> stackRow(JfrAggregationService.RankedEntry rank, boolean stackIds) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("group", rank.group());
        row.put("key", stackIds && rank.stackId() != null && "stack".equals(rank.group()) ? rank.stackId() : rank.key());
        row.put("count", rank.count());
        row.put("bytes", rank.bytes());
        row.put("totalNs", rank.totalNs());
        if (!stackIds && rank.stack() != null) {
            row.put("stack", rank.stack());
        }
        if (rank.matchKey() != null) {
            row.put("matchKey", rank.matchKey());
        }
        if (stackIds && rank.stackId() != null) {
            row.put("stackId", rank.stackId());
            row.put("leaf", rank.leaf());
            row.put("topAppFrame", rank.topAppFrame());
            row.put("depth", rank.depth());
        }
        return row;
    }

    protected static AnalyticsModels.SubsystemResult absent(String id, List<String> expected) {
        return new AnalyticsModels.SubsystemResult(
                id,
                explain(new AnalyticsModels.Availability(AnalyticsModels.AVAIL_ABSENT, expected, List.of())),
                Map.of(),
                null,
                Map.of(),
                List.of("No matching JFR events in this window."));
    }

    protected static Double readDouble(IItem item, String field) {
        Long value = JfrItemAccess.quantityLong(item, field);
        if (value != null) {
            return value.doubleValue();
        }
        String text = JfrItemAccess.text(item, field);
        if (text == null) {
            return null;
        }
        try {
            return Double.parseDouble(text.replace("%", "").trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    protected static long readQuantityBytes(IItem item, String field) {
        Long value = JfrItemAccess.quantityLong(item, field);
        return value == null ? 0L : value;
    }

    protected static double instantToSeconds(long epochNs) {
        return epochNs / 1_000_000_000.0;
    }

    protected static String instantIso(long epochNs) {
        return Instant.ofEpochSecond(epochNs / 1_000_000_000L, (int) (epochNs % 1_000_000_000L))
                .toString();
    }

    protected static List<AnalyticsModels.MetricSeries> seriesOrNull(
            boolean timeseries, List<AnalyticsModels.MetricSeries> series) {
        return timeseries ? series : null;
    }

    protected static boolean timeseries(AnalyticsModels.AnalyticsParams params) {
        return params.timeseriesMode();
    }

    protected static int top(AnalyticsModels.AnalyticsParams params) {
        return params.top();
    }

    protected static AnalyticsModels.Availability avail(List<String> expected, java.util.Set<String> available) {
        return explain(JfrAnalyticsSupport.availability(expected, available));
    }

    private static AnalyticsModels.Availability explain(AnalyticsModels.Availability availability) {
        if (!AnalyticsModels.AVAIL_ABSENT.equals(availability.status())) {
            return availability;
        }
        SignalRegistry.Verdict verdict = SignalRegistry.explain(availability.eventTypesExpected(), SignalRegistry.current());
        if (verdict == null) {
            return availability;
        }
        return new AnalyticsModels.Availability(
                availability.status(),
                availability.eventTypesExpected(),
                availability.eventTypesSeen(),
                verdict.reason(),
                verdict.minJdk(),
                verdict.hint());
    }
}

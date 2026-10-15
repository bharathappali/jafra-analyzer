package io.jafra.analyzer.recordings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.openjdk.jmc.common.item.IItemCollection;

import io.jafra.analyzer.recordings.analytics.AnalyticsModels;
import io.jafra.analyzer.recordings.analytics.CorrelationsEngine;
import io.jafra.analyzer.recordings.analytics.SubsystemBuilder;
import io.jafra.analyzer.recordings.analytics.SubsystemRegistry;
import io.jafra.analyzer.signals.SignalRegistry;

@ApplicationScoped
public class JfrAnalyticsService {
    private static final int DEFAULT_TOP = 15;
    private static final int MAX_TOP = 30;

    @Inject
    SubsystemRegistry registry;

    @Inject
    CorrelationsEngine correlations;

    public AnalyticsModels.AnalyticsDocument timeseries(
            RecordingCatalog.WindowSelection selection,
            Integer top,
            Long bucketSeconds,
            String subsystemFilter,
            boolean stackIds)
            throws IOException {
        return build(selection, true, top, bucketSeconds, subsystemFilter, stackIds);
    }

    public AnalyticsModels.AnalyticsDocument dailyStats(
            RecordingCatalog.WindowSelection selection, Integer top, String subsystemFilter, boolean stackIds)
            throws IOException {
        return build(selection, false, top, null, subsystemFilter, stackIds);
    }

    private AnalyticsModels.AnalyticsDocument build(
            RecordingCatalog.WindowSelection selection,
            boolean timeseriesMode,
            Integer top,
            Long bucketSeconds,
            String subsystemFilter,
            boolean stackIds)
            throws IOException {
        int cappedTop = clampTop(top);
        AnalyticsModels.AnalyticsParams params = new AnalyticsModels.AnalyticsParams(
                cappedTop, bucketSeconds, timeseriesMode, parseFilter(subsystemFilter), stackIds);
        IItemCollection events = JfrEventSummarizer.loadEvents(selection);
        Set<String> available = JfrEventSummarizer.discoverTypes(events).keySet();
        boolean profilerPresent = available.stream().anyMatch(id -> id.startsWith("profiler."));
        TimeBucketGrid grid = timeseriesMode
                ? TimeBucketGrid.of(selection.from(), selection.to(), bucketSeconds)
                : null;
        List<AnalyticsModels.Marker> markers = new ArrayList<>();
        Map<String, AnalyticsModels.SubsystemResult> subsystems = new LinkedHashMap<>();
        SignalRegistry.bind(SignalFactsProbe.from(events, available));
        try {
            for (Map.Entry<String, SubsystemBuilder> entry : registry.all().entrySet()) {
                if (!params.subsystemFilter().isEmpty() && !params.subsystemFilter().contains(entry.getKey())) {
                    continue;
                }
                subsystems.put(entry.getKey(), entry.getValue().build(events, available, grid, params, markers));
            }
            Map<String, Object> correlationMap = new LinkedHashMap<>(correlations.correlate(subsystems));
            if (!markers.isEmpty() && timeseriesMode) {
                correlationMap.put("markers", markers);
            }
            String mode = timeseriesMode ? AnalyticsModels.MODE_TIMESERIES : AnalyticsModels.MODE_DAILY;
            return new AnalyticsModels.AnalyticsDocument(
                    AnalyticsModels.SCHEMA_VERSION,
                    mode,
                    AnalysisEnvelope.from(selection),
                    AnalyticsCoverage.compute(selection, available, profilerPresent),
                    subsystems,
                    correlationMap);
        } finally {
            SignalRegistry.clear();
        }
    }

    private static int clampTop(Integer top) {
        if (top == null) {
            return DEFAULT_TOP;
        }
        if (top <= 0) {
            throw new IllegalArgumentException("top must be positive");
        }
        return Math.min(top, MAX_TOP);
    }

    private static List<String> parseFilter(String subsystemFilter) {
        if (subsystemFilter == null || subsystemFilter.isBlank()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String piece : subsystemFilter.split(",")) {
            if (!piece.isBlank()) {
                ids.add(piece.trim().toLowerCase(Locale.ROOT));
            }
        }
        return ids;
    }
}

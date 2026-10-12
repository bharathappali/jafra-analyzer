package io.jafra.analyzer.recordings.analytics;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.jafra.analyzer.recordings.AnalysisEnvelope;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class AnalyticsModels {
    private AnalyticsModels() {}

    public static final int SCHEMA_VERSION = 1;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AnalyticsDocument(
            int schemaVersion,
            String mode,
            AnalysisEnvelope context,
            DataQuality dataQuality,
            Map<String, SubsystemResult> subsystems,
            Map<String, Object> correlations) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DataQuality(
            long windowDurationSeconds,
            int chunkCount,
            long bytesLoaded,
            double coveragePercent,
            List<Gap> gaps,
            int eventTypeCount,
            boolean profilerPresent,
            boolean jdkPeriodicPresent) {

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Gap(String from, String to, String reason) {}
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubsystemResult(
            String id,
            Availability availability,
            Map<String, Object> rollup,
            List<MetricSeries> series,
            Map<String, Object> evidence,
            List<String> notes) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Availability(
            String status,
            List<String> eventTypesExpected,
            List<String> eventTypesSeen) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MetricSeries(
            String id,
            String label,
            String unit,
            String description,
            List<SeriesPoint> points) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SeriesPoint(String at, Number value, Number mean, Number count, Number max) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Marker(String at, String kind, String magnitude, String subsystemRef) {}

    public record AnalyticsParams(int top, Long bucketSeconds, boolean timeseriesMode, List<String> subsystemFilter) {}

    public static final String MODE_TIMESERIES = "timeseries";
    public static final String MODE_DAILY = "daily_stats";

    public static final String AVAIL_PRESENT = "present";
    public static final String AVAIL_PARTIAL = "partial";
    public static final String AVAIL_ABSENT = "absent";
}

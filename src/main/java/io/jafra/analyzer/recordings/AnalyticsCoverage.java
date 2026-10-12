package io.jafra.analyzer.recordings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.jafra.analyzer.recordings.analytics.AnalyticsModels.DataQuality;

/** Coverage of requested window vs loaded chunk span. */
public final class AnalyticsCoverage {
    private AnalyticsCoverage() {}

    public static DataQuality compute(
            RecordingCatalog.WindowSelection selection, Set<String> available, boolean profilerPresent) {
        Instant from = selection.from();
        Instant to = selection.to();
        long windowSeconds = Math.max(1, to.getEpochSecond() - from.getEpochSecond());
        double coveragePercent = 100.0;
        List<DataQuality.Gap> gaps = new ArrayList<>();
        if (selection.start() != null && selection.stop() != null) {
            if (selection.start().isAfter(from)) {
                gaps.add(new DataQuality.Gap(from.toString(), selection.start().toString(), "no_chunks_before_data"));
            }
            if (selection.stop().isBefore(to)) {
                gaps.add(new DataQuality.Gap(selection.stop().toString(), to.toString(), "no_chunks_after_data"));
            }
            long dataStart = selection.start().getEpochSecond();
            long dataEnd = selection.stop().getEpochSecond();
            long overlapStart = Math.max(from.getEpochSecond(), dataStart);
            long overlapEnd = Math.min(to.getEpochSecond(), dataEnd);
            if (overlapEnd > overlapStart) {
                coveragePercent = 100.0 * (overlapEnd - overlapStart) / windowSeconds;
            } else {
                coveragePercent = 0.0;
            }
        }
        boolean jdkPeriodic = available.contains("jdk.CPULoad")
                || available.contains("jdk.GCHeapSummary")
                || available.contains("jdk.JavaThreadStatistics");
        return new DataQuality(
                windowSeconds,
                selection.chunks(),
                selection.bytes(),
                Math.min(100.0, Math.max(0.0, coveragePercent)),
                gaps.isEmpty() ? null : gaps,
                available.size(),
                profilerPresent,
                jdkPeriodic);
    }
}

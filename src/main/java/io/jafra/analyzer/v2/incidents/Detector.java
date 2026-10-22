package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Map;

import io.jafra.analyzer.v2.store.V2Store.EventRow;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;
import io.jafra.analyzer.v2.store.V2Store.SampleRow;

record WindowInput(
        JvmRow jvm,
        long fromMs,
        long toMs,
        Map<String, RollupRow> totals,
        List<EventRow> events,
        List<SampleRow> samples,
        boolean baselineProven,
        long baselineGcCount,
        Double baselinePauseP99Us) {}

public interface Detector {
    String type();

    String group();

    IncidentLifecycle.Kind kind();

    String family();

    List<Integer> jdks();

    Verdict evaluate(WindowInput input);
}

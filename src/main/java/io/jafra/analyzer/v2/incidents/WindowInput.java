package io.jafra.analyzer.v2.incidents;

import io.jafra.analyzer.v2.store.V2Store.JvmRow;

public record WindowInput(
        JvmRow jvm,
        long fromMs,
        long toMs,
        RollupView window,
        SampleView samples,
        EventView events,
        Baselines baselines,
        JvmSettings settings) {}

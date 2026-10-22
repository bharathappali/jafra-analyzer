package io.jafra.analyzer.v2.catalogue;

import java.util.List;

/** One catalogue metric. Names, units, and stats come only from metric-catalogue.json. */
public record MetricSpec(
        String name,
        String group,
        String unit,
        String kind,
        List<String> stats,
        List<String> by,
        boolean histogram,
        String histogramFamily,
        boolean trend,
        String limit,
        List<Integer> jdk,
        String sourceEvent,
        String sourceField,
        String producer,
        List<String> flags,
        List<String> charts,
        boolean overviewKey,
        String overviewStat,
        String derivation,
        String note) {

    public boolean onJdk(int major) {
        return jdk != null && jdk.contains(major);
    }

    public boolean optInNmt() {
        return flags != null && flags.contains("opt_in_nmt");
    }
}

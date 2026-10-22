package io.jafra.analyzer.v2.catalogue;

import java.util.List;

public record ChartSpec(
        String id,
        String group,
        String title,
        List<ViewSpec> views,
        List<String> metrics,
        List<String> events,
        List<String> frames,
        List<Integer> jdk,
        String note) {

    public boolean onJdk(int major) {
        return jdk != null && jdk.contains(major);
    }
}

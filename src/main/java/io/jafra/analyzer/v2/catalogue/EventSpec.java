package io.jafra.analyzer.v2.catalogue;

import java.util.List;
import java.util.Map;

public record EventSpec(
        String type,
        String group,
        String sourceEvent,
        Map<String, String> fields,
        List<Integer> jdk,
        List<String> flags) {

    public boolean onJdk(int major) {
        return jdk != null && jdk.contains(major);
    }
}

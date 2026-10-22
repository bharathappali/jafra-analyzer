package io.jafra.analyzer.v2.catalogue;

import java.util.List;

public record FrameKindSpec(
        String kind, String unit, List<String> flags, List<String> filters, List<Integer> jdk) {

    public boolean onJdk(int major) {
        return jdk != null && jdk.contains(major);
    }
}

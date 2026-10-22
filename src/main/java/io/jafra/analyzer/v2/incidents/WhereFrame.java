package io.jafra.analyzer.v2.incidents;

import java.util.List;

public record WhereFrame(String frame, double sharePct, List<String> sourceEvents) {
    public WhereFrame {
        sourceEvents = sourceEvents == null ? List.of() : List.copyOf(sourceEvents);
    }
}

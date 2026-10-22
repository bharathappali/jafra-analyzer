package io.jafra.analyzer.v2.incidents;

import java.util.Set;

public record Baseline(Double median, Double mad, int n, long events, Double p99, Set<String> flags) {
    public Baseline {
        flags = flags == null ? Set.of() : Set.copyOf(flags);
    }
}

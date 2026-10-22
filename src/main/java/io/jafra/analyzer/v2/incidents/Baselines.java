package io.jafra.analyzer.v2.incidents;

public interface Baselines {
    Baseline of(String name, String key, String group, String stat);
}

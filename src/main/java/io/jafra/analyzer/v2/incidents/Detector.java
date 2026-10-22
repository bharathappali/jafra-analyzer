package io.jafra.analyzer.v2.incidents;

import java.util.Set;

public interface Detector {
    String type();

    String group();

    IncidentLifecycle.Kind kind();

    String family();

    Set<Integer> jdks();

    WindowVerdict evaluate(WindowInput input);
}

package io.jafra.analyzer.v2.incidents.support;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Full collections are {@code jdk.GarbageCollection.name} values.
 * Default names: G1Full, SerialOld, ParallelOld.
 * G1Full was checked on JDK 21.0.12 recordings. Never treat {@code jdk.OldGarbageCollection}
 * as a full GC: on G1 that event is the concurrent cycle (G1Old).
 */
public final class FullGcClassifier {
    public static final List<String> DEFAULT_NAMES = List.of("G1Full", "SerialOld", "ParallelOld");

    private final Set<String> names;

    public FullGcClassifier(Collection<String> names) {
        this.names = Set.copyOf(names);
    }

    public static FullGcClassifier defaults() {
        return new FullGcClassifier(DEFAULT_NAMES);
    }

    public boolean isFull(String collectorName) {
        return collectorName != null && names.contains(collectorName);
    }
}

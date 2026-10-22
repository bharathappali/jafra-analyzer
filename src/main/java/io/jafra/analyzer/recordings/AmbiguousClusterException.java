package io.jafra.analyzer.recordings;

import java.util.List;

/** A report or file read matched more than one cluster and no {@code ?cluster=} was given. */
public final class AmbiguousClusterException extends RuntimeException {
    private final List<String> clusters;

    public AmbiguousClusterException(List<String> clusters) {
        super("pass ?cluster=<id>");
        this.clusters = List.copyOf(clusters);
    }

    public List<String> clusters() {
        return clusters;
    }
}

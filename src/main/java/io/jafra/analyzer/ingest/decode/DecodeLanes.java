package io.jafra.analyzer.ingest.decode;

import java.util.Objects;

/** Stable lane for one pod so rotated recordings stay in t_start order on the same thread. */
public final class DecodeLanes {
    private DecodeLanes() {}

    public static int of(String namespace, String pod, String container, int lanes) {
        int width = Math.max(1, lanes);
        int hash = Objects.hash(namespace, pod, container);
        return Math.floorMod(hash, width);
    }
}

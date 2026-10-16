package io.jafra.analyzer.ingest.decode;

import java.util.Objects;

/** Stable lane for one recording so its chunks stay in order while other pods run elsewhere. */
public final class DecodeLanes {
    private DecodeLanes() {}

    public static int of(String namespace, String pod, String container, String recording, int lanes) {
        int width = Math.max(1, lanes);
        int hash = Objects.hash(namespace, pod, container, recording);
        return Math.floorMod(hash, width);
    }
}

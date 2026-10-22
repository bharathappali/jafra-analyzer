package io.jafra.analyzer.storage;

/**
 * Striped locks so a chunk's sidecar writers (decoder, repair, migration resume) each finish a
 * full row read and JSON write before the next one starts.
 */
public final class SidecarLocks {
    private static final int STRIPES = 64;
    private static final Object[] LOCKS = new Object[STRIPES];

    static {
        for (int i = 0; i < STRIPES; i++) {
            LOCKS[i] = new Object();
        }
    }

    private SidecarLocks() {}

    public static Object of(String chunkId) {
        int hash = chunkId == null ? 0 : chunkId.hashCode();
        return LOCKS[(hash & Integer.MAX_VALUE) % STRIPES];
    }
}

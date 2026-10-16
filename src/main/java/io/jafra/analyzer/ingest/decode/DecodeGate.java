package io.jafra.analyzer.ingest.decode;

import java.util.function.Consumer;

import io.jafra.analyzer.storage.ChunkMetadata;

/** Test hook at the start of a decode, before any catalog lock is taken. */
public final class DecodeGate {
    public static volatile Consumer<ChunkMetadata> before;

    private DecodeGate() {}

    static void enter(ChunkMetadata metadata) {
        Consumer<ChunkMetadata> hook = before;
        if (hook != null && metadata != null) {
            hook.accept(metadata);
        }
    }
}

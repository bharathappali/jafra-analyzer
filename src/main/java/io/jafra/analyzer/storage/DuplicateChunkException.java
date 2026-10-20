package io.jafra.analyzer.storage;

import java.io.IOException;

/** The chunk id is already in the index, including a pending row from a raced commit. */
public class DuplicateChunkException extends IOException {
    public DuplicateChunkException(String chunkId) {
        super(chunkId);
    }
}

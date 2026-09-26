package io.jafra.analyzer.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChunkFileNameTest {
    @Test
    void searchBlockIsFixedWidthAndRoundTripsTheChunkId() {
        ChunkMetadata metadata = new ChunkMetadata(
                "chunk-a",
                "local-demo/pod/auth-cache/profile-0.jfr",
                "local-demo",
                "default",
                "pod-uid-1",
                "auth-cache",
                "auth-cache",
                "profile-0.jfr",
                0,
                4,
                "checksum",
                1_750_000_000_000_000_000L,
                5_000_000_000L);

        String name = ChunkFileName.fileName(metadata);
        ChunkFileName.Parsed parsed = ChunkFileName.parse(name);

        assertNotNull(parsed);
        assertEquals(ChunkFileName.SEARCH_CHARS, parsed.search().length());
        assertEquals("chunk-a", parsed.chunkId());
        assertEquals("646566756c74", parsed.namespaceBlock());
        assertEquals("pod-uid-", parsed.uidBlock());
        assertEquals(ChunkFileName.bucket(metadata), ChunkFileName.bucket(parsed.startSeconds()));
        assertTrue(ChunkFileName.allows(parsed, "default", "auth-cache", "auth-cache"));
        assertTrue(!ChunkFileName.allows(parsed, "other", null, null));
    }

    @Test
    void shortNamesArePadded() {
        assertEquals("615f5f615f5f", ChunkFileName.encodeWord("a"));
        assertEquals("uid_____", ChunkFileName.encodeUid("uid"));
    }
}

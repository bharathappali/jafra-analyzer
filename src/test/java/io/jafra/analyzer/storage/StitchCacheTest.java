package io.jafra.analyzer.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StitchCacheTest {
    @Test
    void missThenHitReusesSameFile(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        persist(store, "a", "rec-a", new byte[] {1, 2, 3});
        StitchCache cache = new StitchCache(store, 1024, Duration.ofMinutes(5), Clock.systemUTC(), false);

        Path firstPath;
        try (StitchCache.Lease first = cache.acquire("key-a", List.of("rec-a"))) {
            firstPath = first.path();
            assertTrue(Files.exists(firstPath));
            assertEquals(3, Files.size(firstPath));
        }
        try (StitchCache.Lease second = cache.acquire("key-a", List.of("rec-a"))) {
            assertEquals(firstPath, second.path());
            assertEquals(Files.getLastModifiedTime(firstPath), Files.getLastModifiedTime(second.path()));
        }
        cache.close();
    }

    @Test
    void reportAndSummaryShareSameCacheKey(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        persist(store, "a", "rec-a", new byte[] {1, 2});
        StitchCache cache = new StitchCache(store, 1024, Duration.ofMinutes(5), Clock.systemUTC(), false);

        Path shared;
        try (StitchCache.Lease report = cache.acquire("ns|pod|c|rec-a", List.of("rec-a"))) {
            shared = report.path();
        }
        try (StitchCache.Lease summary = cache.acquire("ns|pod|c|rec-a", List.of("rec-a"))) {
            assertEquals(shared, summary.path());
        }
        cache.close();
    }

    @Test
    void coveringReuseWhenFingerprintUnchangedAndSpanFits(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        persist(store, "a", "rec-a", new byte[] {1, 2});
        persist(store, "b", "rec-b", new byte[] {3, 4});
        StitchCache cache = new StitchCache(store, 1024, Duration.ofMinutes(5), Clock.systemUTC(), false);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant stop = Instant.parse("2026-01-01T01:00:00Z");
        String fingerprint = "rec-a:2:1,rec-b:2:1";

        Path wide;
        try (StitchCache.Lease lease = cache.acquireCovering(
                "ns|pod|c|rec-a,rec-b",
                "ns|pod|c",
                List.of("rec-a", "rec-b"),
                start,
                stop,
                start,
                stop,
                fingerprint)) {
            wide = lease.path();
        }
        try (StitchCache.Lease narrower = cache.acquireCovering(
                "ns|pod|c|rec-b",
                "ns|pod|c",
                List.of("rec-b"),
                Instant.parse("2026-01-01T00:30:00Z"),
                stop,
                Instant.parse("2026-01-01T00:30:00Z"),
                stop,
                fingerprint)) {
            assertEquals(wide, narrower.path());
        }
        // New data changes fingerprint → must stitch again under the narrower key.
        Path freshPath;
        try (StitchCache.Lease fresh = cache.acquireCovering(
                "ns|pod|c|rec-b",
                "ns|pod|c",
                List.of("rec-b"),
                Instant.parse("2026-01-01T00:30:00Z"),
                stop,
                Instant.parse("2026-01-01T00:30:00Z"),
                stop,
                fingerprint + ",rec-c:1:1")) {
            freshPath = fresh.path();
            assertEquals("ns|pod|c|rec-b", fresh.key());
        }
        assertTrue(Files.exists(freshPath));
        assertTrue(Files.exists(wide));
        assertTrue(!freshPath.equals(wide));
        cache.close();
    }

    @Test
    void ttlReaperTrashesUnusedEntry(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        persist(store, "a", "rec-a", new byte[] {1, 2, 3, 4});
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        StitchCache cache = new StitchCache(store, 1024, Duration.ofMinutes(2), clock, false);
        Path path;
        try (StitchCache.Lease lease = cache.acquire("key-a", List.of("rec-a"))) {
            path = lease.path();
        }
        assertTrue(Files.exists(path));
        now.set(now.get().plus(Duration.ofMinutes(3)));
        cache.reap();
        assertFalse(Files.exists(path));
        assertFalse(cache.contains("key-a"));
        cache.close();
    }

    @Test
    void budgetReaperTrashesOldestWithoutTouchingChunks(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        persist(store, "a", "rec-a", new byte[] {1, 2, 3, 4});
        persist(store, "b", "rec-b", new byte[] {5, 6, 7, 8});
        persist(store, "c", "rec-c", new byte[] {9, 9, 9, 9});
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        StitchCache cache = new StitchCache(store, 4, Duration.ofHours(1), clock, false);
        Path first;
        try (StitchCache.Lease lease = cache.acquire("k1", List.of("rec-a"))) {
            first = lease.path();
        }
        now.set(now.get().plusSeconds(1));
        try (StitchCache.Lease lease = cache.acquire("k2", List.of("rec-b"))) {
            assertTrue(Files.exists(lease.path()));
        }
        assertFalse(Files.exists(first));
        assertTrue(Files.exists(store.payloadPath("a")));
        assertTrue(Files.exists(store.payloadPath("b")));
        assertTrue(Files.exists(store.payloadPath("c")));
        assertEquals(12, store.chunksBytes());
        cache.close();
    }

    @Test
    void ingestAloneDoesNotPopulateStitchCache(@TempDir Path root) throws Exception {
        ChunkStore store = new ChunkStore(root);
        store.recover();
        StitchCache cache = new StitchCache(store, 1024, Duration.ofMinutes(5), Clock.systemUTC(), false);
        persist(store, "a", "rec-a", new byte[] {1, 2, 3});
        assertEquals(0, cache.totalBytes());
        assertEquals(0, Files.list(cache.cacheDir()).count());
        cache.close();
    }

    private static void persist(ChunkStore store, String chunkId, String recordingId, byte[] payload)
            throws Exception {
        ChunkMetadata metadata = new ChunkMetadata(
                chunkId,
                recordingId,
                "local-demo",
                "default",
                "pod",
                "app",
                "c",
                "profile-0.jfr",
                0,
                payload.length,
                "checksum");
        ChunkStore.IncomingWrite write = store.beginWrite(chunkId);
        write.write(payload);
        store.commit(write, metadata);
    }
}

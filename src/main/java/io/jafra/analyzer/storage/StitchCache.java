package io.jafra.analyzer.storage;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.jboss.logging.Logger;

/**
 * Bounded on-demand stitch cache under {@code stitch-cache/}. Durable chunks remain in {@link ChunkStore}.
 */
public class StitchCache implements Closeable {
    private static final Logger LOG = Logger.getLogger(StitchCache.class);

    private final ChunkStore store;
    private final Path cacheDir;
    private final long maxBytes;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();

    public StitchCache(ChunkStore store, long maxBytes, Duration ttl) throws IOException {
        this(store, maxBytes, ttl, Clock.systemUTC(), true);
    }

    public StitchCache(ChunkStore store, long maxBytes, Duration ttl, Clock clock, boolean startReaper)
            throws IOException {
        this.store = store;
        this.cacheDir = store.root().resolve("stitch-cache");
        this.maxBytes = Math.max(1, maxBytes);
        this.ttl = ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(2) : ttl;
        this.clock = clock;
        Files.createDirectories(cacheDir);
        wipeCacheDir();
        if (startReaper) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "jafra-stitch-cache-reaper");
                thread.setDaemon(true);
                return thread;
            });
            this.scheduler.scheduleAtFixedRate(this::reapQuietly, 15, 15, TimeUnit.SECONDS);
        } else {
            this.scheduler = null;
        }
    }

    public Path cacheDir() {
        return cacheDir;
    }

    public long maxBytes() {
        return maxBytes;
    }

    public Duration ttl() {
        return ttl;
    }

    public long totalBytes() {
        return entries.values().stream().mapToLong(entry -> entry.size).sum();
    }

    public boolean contains(String key) {
        Entry entry = entries.get(key);
        return entry != null && Files.isRegularFile(entry.path);
    }

    /**
     * Acquire a lease on a stitched JFR for the given recording ids (cache-before-create).
     * Caller must {@link Lease#close()} when done analyzing.
     */
    public Lease acquire(String key, List<String> recordingIds) throws IOException {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("cache key required");
        }
        if (recordingIds == null || recordingIds.isEmpty()) {
            throw new IllegalArgumentException("recording ids required");
        }
        lock.lock();
        try {
            Entry existing = entries.get(key);
            if (existing != null && Files.isRegularFile(existing.path)) {
                existing.refcount++;
                existing.lastUsed = clock.instant();
                return new Lease(key, existing.path);
            }
            if (existing != null) {
                entries.remove(key);
                ChunkStore.deleteQuietly(existing.path);
            }
            long needed = store.contiguousBytes(recordingIds);
            if (needed <= 0) {
                throw new IOException("no contiguous chunks to stitch for " + key);
            }
            makeRoom(needed);
            if (totalBytes() + needed > maxBytes) {
                throw new IOException("stitch-cache budget exceeded (%d needed, max %d, used %d)"
                        .formatted(needed, maxBytes, totalBytes()));
            }
            Path target = cacheDir.resolve(fileNameFor(key));
            long written = store.stitchTo(target, recordingIds);
            Entry created = new Entry(target, written, clock.instant(), 1);
            entries.put(key, created);
            return new Lease(key, target);
        } finally {
            lock.unlock();
        }
    }

    public void reap() {
        lock.lock();
        try {
            Instant now = clock.instant();
            Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Entry> item = iterator.next();
                Entry entry = item.getValue();
                if (entry.refcount > 0) {
                    continue;
                }
                if (!entry.lastUsed.plus(ttl).isAfter(now)) {
                    ChunkStore.deleteQuietly(entry.path);
                    iterator.remove();
                }
            }
            if (totalBytes() > maxBytes) {
                evictOldestUnlocked(0);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        lock.lock();
        try {
            for (Entry entry : entries.values()) {
                ChunkStore.deleteQuietly(entry.path);
            }
            entries.clear();
        } finally {
            lock.unlock();
        }
    }

    private void makeRoom(long needed) {
        if (totalBytes() + needed <= maxBytes) {
            return;
        }
        evictOldestUnlocked(needed);
    }

    private void evictOldestUnlocked(long needed) {
        List<Map.Entry<String, Entry>> victims = new ArrayList<>();
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            if (item.getValue().refcount == 0) {
                victims.add(item);
            }
        }
        victims.sort(Comparator.comparing(item -> item.getValue().lastUsed));
        for (Map.Entry<String, Entry> victim : victims) {
            if (needed > 0 && totalBytes() + needed <= maxBytes) {
                break;
            }
            if (needed <= 0 && totalBytes() <= maxBytes) {
                break;
            }
            ChunkStore.deleteQuietly(victim.getValue().path);
            entries.remove(victim.getKey());
        }
    }

    private void wipeCacheDir() throws IOException {
        if (!Files.isDirectory(cacheDir)) {
            return;
        }
        try (var stream = Files.list(cacheDir)) {
            stream.forEach(ChunkStore::deleteQuietly);
        }
    }

    private void reapQuietly() {
        try {
            reap();
        } catch (Exception error) {
            LOG.warnf(error, "stitch-cache reaper failed");
        }
    }

    private static String fileNameFor(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32) + ".jfr";
        } catch (NoSuchAlgorithmException error) {
            return ChunkStore.safe(key) + ".jfr";
        }
    }

    private void release(String key) {
        lock.lock();
        try {
            Entry entry = entries.get(key);
            if (entry == null) {
                return;
            }
            if (entry.refcount > 0) {
                entry.refcount--;
            }
            entry.lastUsed = clock.instant();
        } finally {
            lock.unlock();
        }
    }

    public final class Lease implements Closeable {
        private final String key;
        private final Path path;
        private final AtomicBoolean closed = new AtomicBoolean();

        Lease(String key, Path path) {
            this.key = key;
            this.path = path;
        }

        public Path path() {
            return path;
        }

        public String key() {
            return key;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release(key);
            }
        }
    }

    private static final class Entry {
        private final Path path;
        private final long size;
        private Instant lastUsed;
        private int refcount;

        private Entry(Path path, long size, Instant lastUsed, int refcount) {
            this.path = path;
            this.size = size;
            this.lastUsed = lastUsed;
            this.refcount = refcount;
        }
    }
}

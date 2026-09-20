package io.jafra.analyzer.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class AnalyzerStorage {
    private StitchCache stitchCache;

    @Produces
    @Singleton
    ChunkStore chunkStore(
            @ConfigProperty(name = "jafra.storage.root", defaultValue = "/var/lib/jafra/analyzer") String root)
            throws IOException {
        Path path = Path.of(root);
        Files.createDirectories(path);
        ChunkStore store = new ChunkStore(path);
        store.recover();
        return store;
    }

    @Produces
    @Singleton
    StitchCache stitchCache(
            ChunkStore store,
            @ConfigProperty(name = "jafra.storage.stitch-cache.max-bytes", defaultValue = "2147483648")
                    long maxBytes,
            @ConfigProperty(name = "jafra.storage.stitch-cache.ttl", defaultValue = "PT2M") String ttl)
            throws IOException {
        stitchCache = new StitchCache(store, maxBytes, Duration.parse(ttl));
        return stitchCache;
    }

    void warmup(@Observes StartupEvent event, ChunkStore store, StitchCache cache) {
        store.durableChunkCount();
        cache.totalBytes();
    }

    void shutdown(@Observes ShutdownEvent event) {
        if (stitchCache != null) {
            stitchCache.close();
        }
    }
}

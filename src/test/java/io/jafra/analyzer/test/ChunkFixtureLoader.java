package io.jafra.analyzer.test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;

/** Loads committed golden JFR chunk pairs from {@code jfr-fixtures/chunks/} into {@link ChunkStore}. */
public final class ChunkFixtureLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREFIX = "jfr-fixtures/chunks/";

    private ChunkFixtureLoader() {}

    public static GoldenWorkload loadAll(ChunkStore store) throws IOException {
        List<String> stems = listStems();
        for (String stem : stems) {
            persist(store, stem);
        }
        return GoldenWorkload.DEFAULT;
    }

    public static void persist(ChunkStore store, String stem) throws IOException {
        ChunkMetadata metadata = readMetadata(stem);
        byte[] payload = readBytes(stem + ".jfr");
        ChunkStore.IncomingWrite write = store.beginWrite(metadata.chunkId());
        write.write(payload);
        store.commit(write, metadata);
    }

    private static List<String> listStems() throws IOException {
        List<String> stems = new ArrayList<>();
        try (InputStream index = ChunkFixtureLoader.class.getClassLoader().getResourceAsStream(PREFIX + "jfr-fixtures.json")) {
            if (index != null) {
                GoldenManifest manifest = MAPPER.readValue(index, GoldenManifest.class);
                stems.addAll(manifest.chunkIds());
                return stems;
            }
        }
        throw new IOException("missing fixture manifest: " + PREFIX + "jfr-fixtures.json");
    }

    private static ChunkMetadata readMetadata(String stem) throws IOException {
        try (InputStream in = resource(stem + ".meta")) {
            return MAPPER.readValue(in, ChunkMetadata.class);
        }
    }

    private static byte[] readBytes(String name) throws IOException {
        try (InputStream in = resource(name)) {
            return in.readAllBytes();
        }
    }

    private static InputStream resource(String name) throws IOException {
        InputStream in = ChunkFixtureLoader.class.getClassLoader().getResourceAsStream(PREFIX + name);
        if (in == null) {
            throw new IOException("missing fixture resource: " + PREFIX + name);
        }
        return in;
    }

    public record GoldenManifest(String namespace, String pod, String container, List<String> chunkIds) {}

    public record GoldenWorkload(String namespace, String pod, String container) {
        public static final GoldenWorkload DEFAULT =
                new GoldenWorkload("default", "auth-cache-84cd9fbc49-fttcq", "auth-cache");
    }
}

package io.jafra.analyzer.storage;

import java.nio.file.Path;

/**
 * Hierarchical location of one chunk under {@code data/recordings}. Times in the file name are the
 * same epoch-second floor and ceil that the legacy search block used.
 */
public final class ChunkPaths {
    public static final String JVM_INSTANCE_DEFAULT = "default";

    private ChunkPaths() {}

    /** Path of the {@code .jfr} file relative to {@code data/recordings/}. */
    public static String relative(ChunkMetadata metadata) {
        String cluster = cluster(metadata.clusterId());
        String namespace = segment("namespace", metadata.namespace());
        String pod = segment("pod", metadata.podName());
        String container = segment("container", metadata.containerName());
        String podUid = segment("pod_uid", metadata.podUid());
        String chunkId = segment("chunk_id", metadata.chunkId());
        String file = hex8(startSeconds(metadata.chunkStartTimeNs()))
                + "-"
                + hex8(endSeconds(metadata.chunkStartTimeNs(), metadata.chunkDurationNs()))
                + "-"
                + chunkId
                + ".jfr";
        return cluster
                + "/"
                + namespace
                + "/"
                + pod
                + "/"
                + container
                + "/chunks/"
                + podUid
                + "/"
                + JVM_INSTANCE_DEFAULT
                + "/"
                + file;
    }

    public static Path payload(Path storageRoot, ChunkMetadata metadata) {
        return storageRoot.resolve("data").resolve("recordings").resolve(relative(metadata));
    }

    public static Path sidecar(Path storageRoot, ChunkMetadata metadata) {
        Path payload = payload(storageRoot, metadata);
        String name = payload.getFileName().toString();
        return payload.resolveSibling(name.substring(0, name.length() - ".jfr".length()) + ".json");
    }

    private static String cluster(String clusterId) {
        if (clusterId == null || clusterId.isBlank()) {
            return "default";
        }
        return segment("cluster", clusterId);
    }

    private static String segment(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidIdentityException(label + " is empty");
        }
        if (value.equals(".")
                || value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0
                || value.contains("..")) {
            throw new InvalidIdentityException(label + " is not a single path segment");
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new InvalidIdentityException(label + " contains a control character");
            }
        }
        return value;
    }

    static long startSeconds(long startNs) {
        if (startNs <= 0) {
            return 0;
        }
        return startNs / 1_000_000_000L;
    }

    static long endSeconds(long startNs, long durationNs) {
        if (startNs <= 0) {
            return 0;
        }
        long endNs = startNs + Math.max(0, durationNs);
        if (endNs <= startNs) {
            return startSeconds(startNs);
        }
        return (endNs + 1_000_000_000L - 1) / 1_000_000_000L;
    }

    private static String hex8(long value) {
        long safe = value;
        if (safe < 0) {
            safe = 0;
        }
        if (safe > 0xffffffffL) {
            safe = 0xffffffffL;
        }
        return String.format("%08x", safe);
    }
}

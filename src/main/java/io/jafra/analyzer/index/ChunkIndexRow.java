package io.jafra.analyzer.index;

import java.time.Instant;

/** One row of the chunks table. This type does not talk to DuckDB. */
public record ChunkIndexRow(
        String chunkId,
        String recordingId,
        String clusterId,
        String nodeName,
        String namespace,
        String podName,
        String podUid,
        String containerName,
        String jvmInstance,
        String physicalFile,
        Integer fileSequence,
        Integer chunkSequence,
        Long chunkOffset,
        long chunkLength,
        long startNs,
        long endNs,
        long minEventStartNs,
        long maxEventEndNs,
        String checksum,
        String path,
        String status,
        Instant receivedAt,
        Instant decodedAt) {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_STORED = "stored";
    public static final String STATUS_DELETING = "deleting";
    public static final String JVM_INSTANCE_DEFAULT = "default";

    public ChunkIndexRow {
        if (jvmInstance == null || jvmInstance.isBlank()) {
            jvmInstance = JVM_INSTANCE_DEFAULT;
        }
    }
}

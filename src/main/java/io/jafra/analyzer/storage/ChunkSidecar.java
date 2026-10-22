package io.jafra.analyzer.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jafra.analyzer.index.ChunkIndexRow;

/**
 * JSON next to a chunk payload. Older sidecars only have the {@link ChunkMetadata} fields; the
 * extra identity and event-range fields are optional on read.
 */
public final class ChunkSidecar {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChunkSidecar() {}

    public static Document read(Path file) throws IOException {
        return MAPPER.readValue(file.toFile(), Document.class);
    }

    public static void write(Path target, Document document) throws IOException {
        DurableFiles.writeAtomic(target, MAPPER.writeValueAsBytes(document));
    }

    public static Path beside(Path payload) {
        String name = payload.getFileName().toString();
        String stem = name.endsWith(".jfr") ? name.substring(0, name.length() - 4) : name;
        return payload.resolveSibling(stem + ".json");
    }

    public static Document from(
            ChunkMetadata metadata,
            String nodeName,
            Integer fileSequence,
            Integer chunkSequence,
            String jvmInstance,
            long minEventStartNs,
            long maxEventEndNs) {
        Document document = fromMetadata(metadata);
        document.nodeName = nodeName;
        document.fileSequence = fileSequence;
        document.chunkSequence = chunkSequence;
        document.jvmInstance = jvmInstance == null || jvmInstance.isBlank()
                ? ChunkIndexRow.JVM_INSTANCE_DEFAULT
                : jvmInstance;
        document.minEventStartNs = minEventStartNs;
        document.maxEventEndNs = maxEventEndNs;
        return document;
    }

    public static Document fromRow(ChunkIndexRow row) {
        long duration = Math.max(0, row.endNs() - row.startNs());
        ChunkMetadata metadata = new ChunkMetadata(
                row.chunkId(),
                row.recordingId(),
                row.clusterId(),
                row.namespace(),
                row.podUid(),
                row.podName(),
                row.containerName(),
                row.physicalFile() == null ? "" : row.physicalFile(),
                row.chunkOffset() == null ? 0L : row.chunkOffset(),
                row.chunkLength(),
                row.checksum(),
                row.startNs(),
                duration);
        return from(
                metadata,
                row.nodeName(),
                row.fileSequence(),
                row.chunkSequence(),
                row.jvmInstance(),
                row.minEventStartNs(),
                row.maxEventEndNs());
    }

    /**
     * Rewrites an existing sidecar so its event window matches the index row. A missing sidecar is
     * left absent; creating one is the repair thread's job.
     */
    public static void syncEventRange(Path sidecar, ChunkIndexRow row) throws IOException {
        if (!Files.isRegularFile(sidecar)) {
            return;
        }
        Document document = read(sidecar);
        document.minEventStartNs = row.minEventStartNs();
        document.maxEventEndNs = row.maxEventEndNs();
        if (document.jvmInstance == null || document.jvmInstance.isBlank()) {
            document.jvmInstance = row.jvmInstance();
        }
        if (document.nodeName == null) {
            document.nodeName = row.nodeName();
        }
        if (document.fileSequence == null) {
            document.fileSequence = row.fileSequence();
        }
        if (document.chunkSequence == null) {
            document.chunkSequence = row.chunkSequence();
        }
        write(sidecar, document);
    }

    public static ChunkMetadata metadata(Document document) {
        return new ChunkMetadata(
                document.chunkId,
                document.recordingId,
                document.clusterId,
                document.namespace,
                document.podUid,
                document.podName,
                document.containerName,
                document.physicalFilename,
                document.chunkOffset,
                document.chunkLength,
                document.checksum,
                document.chunkStartTimeNs,
                document.chunkDurationNs);
    }

    private static Document fromMetadata(ChunkMetadata metadata) {
        Document document = new Document();
        document.chunkId = metadata.chunkId();
        document.recordingId = metadata.recordingId();
        document.clusterId = metadata.clusterId();
        document.namespace = metadata.namespace();
        document.podUid = metadata.podUid();
        document.podName = metadata.podName();
        document.containerName = metadata.containerName();
        document.physicalFilename = metadata.physicalFilename();
        document.chunkOffset = metadata.chunkOffset();
        document.chunkLength = metadata.chunkLength();
        document.checksum = metadata.checksum();
        document.chunkStartTimeNs = metadata.chunkStartTimeNs();
        document.chunkDurationNs = metadata.chunkDurationNs();
        return document;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Document {
        public String chunkId;
        public String recordingId;
        public String clusterId;
        public String namespace;
        public String podUid;
        public String podName;
        public String containerName;
        public String physicalFilename;
        public long chunkOffset;
        public long chunkLength;
        public String checksum;
        public long chunkStartTimeNs;
        public long chunkDurationNs;
        public String nodeName;
        public Integer fileSequence;
        public Integer chunkSequence;
        public String jvmInstance;
        public Long minEventStartNs;
        public Long maxEventEndNs;
    }
}

package io.jafra.analyzer.storage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChunkMetadata(
        @JsonProperty("chunkId") String chunkId,
        @JsonProperty("recordingId") String recordingId,
        @JsonProperty("clusterId") String clusterId,
        @JsonProperty("namespace") String namespace,
        @JsonProperty("podUid") String podUid,
        @JsonProperty("podName") String podName,
        @JsonProperty("containerName") String containerName,
        @JsonProperty("physicalFilename") String physicalFilename,
        @JsonProperty("chunkOffset") long chunkOffset,
        @JsonProperty("chunkLength") long chunkLength,
        @JsonProperty("checksum") String checksum,
        @JsonProperty("chunkStartTimeNs") long chunkStartTimeNs,
        @JsonProperty("chunkDurationNs") long chunkDurationNs) {

    public ChunkMetadata {
        if (podName == null) {
            podName = "";
        }
        if (namespace == null) {
            namespace = "";
        }
        if (containerName == null) {
            containerName = "";
        }
        if (physicalFilename == null) {
            physicalFilename = "";
        }
    }

    public ChunkMetadata(
            String chunkId,
            String recordingId,
            String clusterId,
            String namespace,
            String podUid,
            String podName,
            String containerName,
            String physicalFilename,
            long chunkOffset,
            long chunkLength,
            String checksum) {
        this(
                chunkId,
                recordingId,
                clusterId,
                namespace,
                podUid,
                podName,
                containerName,
                physicalFilename,
                chunkOffset,
                chunkLength,
                checksum,
                0,
                0);
    }

    public ChunkMetadata(
            String chunkId,
            String recordingId,
            String clusterId,
            String namespace,
            String podUid,
            String containerName,
            String physicalFilename,
            long chunkOffset,
            long chunkLength,
            String checksum) {
        this(
                chunkId,
                recordingId,
                clusterId,
                namespace,
                podUid,
                "",
                containerName,
                physicalFilename,
                chunkOffset,
                chunkLength,
                checksum,
                0,
                0);
    }
}

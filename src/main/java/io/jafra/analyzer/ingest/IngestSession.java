package io.jafra.analyzer.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import io.jafra.ingest.v1.AckStatus;
import io.jafra.ingest.v1.ChunkFrame;
import io.jafra.ingest.v1.CommitChunk;
import io.jafra.ingest.v1.OpenChunk;
import io.jafra.ingest.v1.UploadAck;

public final class IngestSession {
    public static final int PROTOCOL_VERSION = 1;
    public static final int DEFAULT_MAX_METADATA = 4096;

    private final int maxMetadataLength;
    private OpenChunk open;
    private String expectedChunkId;
    private MessageDigest digest;
    private long receivedBytes;
    private long nextFrameOffset;
    private boolean committed;
    private FrameSink sink;

    public IngestSession() {
        this(DEFAULT_MAX_METADATA);
    }

    public IngestSession(int maxMetadataLength) {
        this.maxMetadataLength = maxMetadataLength;
    }

    public synchronized void attachSink(FrameSink sink) {
        this.sink = sink;
    }

    public synchronized OpenResult open(OpenChunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        if (open != null) {
            return OpenResult.reject("OpenChunk already received");
        }
        String reason = validateOpen(chunk);
        if (reason != null) {
            return OpenResult.reject(reason);
        }
        this.open = chunk;
        this.expectedChunkId = chunkId(chunk);
        this.digest = sha256();
        this.receivedBytes = 0;
        this.nextFrameOffset = 0;
        return OpenResult.ok(expectedChunkId);
    }

    public synchronized FrameResult frame(ChunkFrame frame) {
        if (open == null) {
            return FrameResult.reject("missing OpenChunk");
        }
        if (committed) {
            return FrameResult.reject("chunk already committed");
        }
        if (!expectedChunkId.equals(frame.getChunkId()) || !open.getRecordingId().equals(frame.getRecordingId())) {
            return FrameResult.reject("frame identity mismatch");
        }
        if (frame.getFrameOffset() != nextFrameOffset) {
            if (frame.getFrameOffset() < nextFrameOffset) {
                return FrameResult.reject("overlapping frame");
            }
            return FrameResult.reject("frame offset gap");
        }
        int payloadSize = frame.getPayload().size();
        if (payloadSize == 0) {
            return FrameResult.reject("empty frame");
        }
        if (receivedBytes + payloadSize > open.getChunkLength()) {
            return FrameResult.reject("frame exceeds declared size");
        }
        byte[] payload = frame.getPayload().toByteArray();
        if (sink != null) {
            try {
                sink.accept(payload);
            } catch (java.io.IOException error) {
                return FrameResult.retry("failed to persist frame: " + error.getMessage());
            }
        }
        digest.update(payload);
        receivedBytes += payloadSize;
        nextFrameOffset += payloadSize;
        return FrameResult.ok(receivedBytes);
    }

    public synchronized UploadAck commit(CommitChunk commit) {
        if (open == null) {
            return ack("", commit.getChunkId(), AckStatus.REJECTED, "missing OpenChunk", 0);
        }
        if (committed) {
            return ack(open.getRecordingId(), expectedChunkId, AckStatus.DUPLICATE, "already committed", receivedBytes);
        }
        if (!expectedChunkId.equals(commit.getChunkId()) || !open.getRecordingId().equals(commit.getRecordingId())) {
            return ack(open.getRecordingId(), expectedChunkId, AckStatus.REJECTED, "commit identity mismatch", receivedBytes);
        }
        if (receivedBytes != open.getChunkLength() || receivedBytes != commit.getTotalBytes()) {
            return ack(open.getRecordingId(), expectedChunkId, AckStatus.REJECTED, "incorrect commit length", receivedBytes);
        }
        if (!"sha256".equalsIgnoreCase(commit.getChecksumAlgorithm())) {
            return ack(open.getRecordingId(), expectedChunkId, AckStatus.REJECTED, "unsupported checksum algorithm", receivedBytes);
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(commit.getChecksum())) {
            return ack(open.getRecordingId(), expectedChunkId, AckStatus.REJECTED, "incorrect checksum", receivedBytes);
        }
        committed = true;
        return ack(open.getRecordingId(), expectedChunkId, AckStatus.ACCEPTED, "accepted", receivedBytes);
    }

    public synchronized boolean isCommitted() {
        return committed;
    }

    public synchronized OpenChunk openChunk() {
        return open;
    }

    public synchronized long receivedBytes() {
        return receivedBytes;
    }

    public synchronized int framesEstimate() {
        if (open == null || receivedBytes == 0) {
            return 0;
        }
        return (int) Math.max(1, (receivedBytes + 131071) / 131072);
    }

    public static String chunkId(OpenChunk chunk) {
        String material = chunk.getClusterId() + '|' + chunk.getPodUid() + '|' + chunk.getContainerName()
                + '|' + chunk.getPhysicalFilename() + '|' + chunk.getChunkOffset() + '|' + chunk.getChunkLength();
        return HexFormat.of().formatHex(sha256().digest(material.getBytes(StandardCharsets.UTF_8)));
    }

    private String validateOpen(OpenChunk chunk) {
        if (chunk.getProtocolVersion() != PROTOCOL_VERSION) {
            return "unsupported protocol version";
        }
        if (chunk.getChunkLength() <= 0) {
            return "empty chunk rejection";
        }
        if (isBlank(chunk.getClusterId()) || isBlank(chunk.getPodUid()) || isBlank(chunk.getContainerName())
                || isBlank(chunk.getPhysicalFilename()) || isBlank(chunk.getRecordingId())) {
            return "missing required identity fields";
        }
        if (tooLong(chunk.getClusterId()) || tooLong(chunk.getNodeName()) || tooLong(chunk.getNamespace())
                || tooLong(chunk.getPodName()) || tooLong(chunk.getPodUid()) || tooLong(chunk.getContainerName())
                || tooLong(chunk.getRecordingId()) || tooLong(chunk.getPhysicalFilename())) {
            return "metadata exceeds maximum length";
        }
        return null;
    }

    private boolean tooLong(String value) {
        return value != null && value.length() > maxMetadataLength;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static UploadAck ack(String recordingId, String chunkId, AckStatus status, String message, long bytes) {
        return UploadAck.newBuilder()
                .setRecordingId(recordingId)
                .setChunkId(chunkId)
                .setStatus(status)
                .setMessage(message)
                .setReceivedBytes(bytes)
                .build();
    }

    public record OpenResult(boolean accepted, String chunkId, String reason) {
        static OpenResult ok(String chunkId) {
            return new OpenResult(true, chunkId, null);
        }

        static OpenResult reject(String reason) {
            return new OpenResult(false, null, reason);
        }
    }

    public record FrameResult(boolean accepted, boolean retry, long receivedBytes, String reason) {
        static FrameResult ok(long receivedBytes) {
            return new FrameResult(true, false, receivedBytes, null);
        }

        static FrameResult reject(String reason) {
            return new FrameResult(false, false, 0, reason);
        }

        static FrameResult retry(String reason) {
            return new FrameResult(false, true, 0, reason);
        }
    }

    @FunctionalInterface
    public interface FrameSink {
        void accept(byte[] payload) throws java.io.IOException;
    }
}

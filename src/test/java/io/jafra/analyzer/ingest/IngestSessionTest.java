package io.jafra.analyzer.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;

import io.jafra.ingest.v1.AckStatus;
import io.jafra.ingest.v1.ChunkFrame;
import io.jafra.ingest.v1.CommitChunk;
import io.jafra.ingest.v1.OpenChunk;

class IngestSessionTest {
    @Test
    void validChunkStream() throws Exception {
        byte[] payload = "finalized-jfr-chunk".getBytes(StandardCharsets.UTF_8);
        IngestSession session = new IngestSession();
        OpenChunk open = open(payload.length);
        assertTrue(session.open(open).accepted());
        assertTrue(session.frame(frame(open, 0, payload)).accepted());
        var ack = session.commit(commit(open, payload));
        assertEquals(AckStatus.ACCEPTED, ack.getStatus());
    }

    @Test
    void missingOpenChunk() {
        IngestSession session = new IngestSession();
        assertFalse(session.frame(ChunkFrame.newBuilder()
                .setRecordingId("r")
                .setChunkId("c")
                .setPayload(ByteString.copyFromUtf8("x"))
                .build()).accepted());
    }

    @Test
    void frameOffsetGap() {
        IngestSession session = opened(8);
        assertTrue(session.frame(frame(session.openChunk(), 0, new byte[4])).accepted());
        assertTrue(session.frame(frame(session.openChunk(), 6, new byte[2])).reason().contains("gap"));
    }

    @Test
    void overlappingFrame() {
        IngestSession session = opened(8);
        assertTrue(session.frame(frame(session.openChunk(), 0, new byte[4])).accepted());
        assertTrue(session.frame(frame(session.openChunk(), 0, new byte[4])).reason().contains("overlapping"));
    }

    @Test
    void frameExceedsDeclaredSize() {
        IngestSession session = opened(4);
        assertTrue(session.frame(frame(session.openChunk(), 0, new byte[8])).reason().contains("exceeds"));
    }

    @Test
    void incorrectCommitLength() throws Exception {
        byte[] payload = new byte[4];
        IngestSession session = opened(4);
        session.frame(frame(session.openChunk(), 0, payload));
        var commit = commit(session.openChunk(), payload).toBuilder().setTotalBytes(3).build();
        assertEquals(AckStatus.REJECTED, session.commit(commit).getStatus());
    }

    @Test
    void incorrectChecksum() throws Exception {
        byte[] payload = new byte[] {1, 2, 3, 4};
        IngestSession session = opened(4);
        session.frame(frame(session.openChunk(), 0, payload));
        var commit = commit(session.openChunk(), payload).toBuilder().setChecksum("deadbeef").build();
        assertEquals(AckStatus.REJECTED, session.commit(commit).getStatus());
    }

    @Test
    void emptyChunkRejection() {
        IngestSession session = new IngestSession();
        OpenChunk open = open(0);
        assertFalse(session.open(open).accepted());
    }

    @Test
    void unsupportedProtocolVersion() {
        IngestSession session = new IngestSession();
        OpenChunk open = open(4).toBuilder().setProtocolVersion(99).build();
        assertTrue(session.open(open).reason().contains("protocol version"));
    }

    @Test
    void unknownProtobufFieldsAreIgnored() throws Exception {
        OpenChunk original = open(4);
        byte[] extraField = new byte[] { (byte) 0xC8, 0x06, 0x01 };
        byte[] encoded = new byte[original.toByteArray().length + extraField.length];
        System.arraycopy(original.toByteArray(), 0, encoded, 0, original.toByteArray().length);
        System.arraycopy(extraField, 0, encoded, original.toByteArray().length, extraField.length);
        OpenChunk parsed = OpenChunk.parseFrom(encoded);
        assertEquals(1, parsed.getProtocolVersion());
        assertEquals("local-demo", parsed.getClusterId());
        assertTrue(parsed.getUnknownFields().asMap().containsKey(105));
        IngestSession session = new IngestSession();
        assertTrue(session.open(parsed).accepted());
    }

    @Test
    void maximumMetadataLengthEnforcement() {
        IngestSession session = new IngestSession(8);
        OpenChunk open = open(4).toBuilder().setClusterId("0123456789").build();
        assertTrue(session.open(open).reason().contains("maximum length"));
    }

    private static IngestSession opened(int length) {
        IngestSession session = new IngestSession();
        assertTrue(session.open(open(length)).accepted());
        return session;
    }

    private static OpenChunk open(int length) {
        return OpenChunk.newBuilder()
                .setProtocolVersion(1)
                .setClusterId("local-demo")
                .setNodeName("worker-1")
                .setNamespace("default")
                .setPodName("auth-cache")
                .setPodUid("pod-uid")
                .setContainerName("auth-cache")
                .setRecordingId("local-demo/pod-uid/auth-cache/profile-0.jfr")
                .setPhysicalFilename("profile-0.jfr")
                .setChunkLength(length)
                .build();
    }

    private static ChunkFrame frame(OpenChunk open, long offset, byte[] payload) {
        return ChunkFrame.newBuilder()
                .setRecordingId(open.getRecordingId())
                .setChunkId(IngestSession.chunkId(open))
                .setFrameOffset(offset)
                .setPayload(ByteString.copyFrom(payload))
                .build();
    }

    private static CommitChunk commit(OpenChunk open, byte[] payload) throws Exception {
        return CommitChunk.newBuilder()
                .setRecordingId(open.getRecordingId())
                .setChunkId(IngestSession.chunkId(open))
                .setTotalBytes(payload.length)
                .setChecksumAlgorithm("sha256")
                .setChecksum(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)))
                .build();
    }
}

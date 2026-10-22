package io.jafra.analyzer.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;

class EventRangeDecoderTest {
    @Test
    void eventBeforeTheClaimedStartWidensTheRange(@TempDir Path root) throws Exception {
        IndexStore index = new IndexStore(root);
        ChunkStore store = new ChunkStore(root, index);
        EventRangeDecoder decoder = new EventRangeDecoder(index, root, 1);
        try {
            store.onStored(decoder::offer);
            store.recover();
            Path dump = Files.createTempFile("jafra-range", ".jfr");
            try (Recording recording = new Recording()) {
                recording.enable(RangeMarker.class);
                recording.start();
                RangeMarker marker = new RangeMarker();
                marker.token = "early";
                marker.commit();
                recording.stop();
                recording.dump(dump);
            }
            byte[] payload = Files.readAllBytes(dump);
            Files.deleteIfExists(dump);
            long claimedStart = System.currentTimeMillis() * 1_000_000L + Duration.ofHours(1).toNanos();
            ChunkMetadata metadata = new ChunkMetadata(
                    "early-chunk",
                    "rec",
                    "default",
                    "ns",
                    "pod-uid",
                    "payments-api",
                    "app",
                    "profile.jfr",
                    0,
                    payload.length,
                    "checksum",
                    claimedStart,
                    Duration.ofMinutes(1).toNanos());
            ChunkStore.IncomingWrite write = store.beginWrite("early-chunk");
            write.write(payload);
            store.commit(write, metadata);

            ChunkIndexRow row = awaitDecoded(index, "early-chunk");
            assertNotNull(row.decodedAt());
            assertTrue(row.minEventStartNs() < claimedStart, "event start should precede the claimed chunk start");
        } finally {
            decoder.close();
            store.close();
            index.close();
        }
    }

    @Test
    void unreadableChunkIsMarkedDecodedWithoutShrinking(@TempDir Path root) throws Exception {
        IndexStore index = new IndexStore(root);
        ChunkStore store = new ChunkStore(root, index);
        EventRangeDecoder decoder = new EventRangeDecoder(index, root, 1);
        try {
            store.recover();
            byte[] payload = new byte[] {1, 2, 3, 4};
            ChunkMetadata metadata = new ChunkMetadata(
                    "garbage",
                    "rec",
                    "default",
                    "ns",
                    "pod-uid",
                    "payments-api",
                    "app",
                    "profile.jfr",
                    0,
                    payload.length,
                    "checksum",
                    500,
                    50);
            ChunkStore.IncomingWrite write = store.beginWrite("garbage");
            write.write(payload);
            store.commit(write, metadata);
            decoder.decode("garbage");
            ChunkIndexRow row = index.get("garbage");
            assertNotNull(row.decodedAt());
            assertTrue(row.minEventStartNs() == 500);
            assertTrue(row.maxEventEndNs() == 550);
        } finally {
            decoder.close();
            store.close();
            index.close();
        }
    }

    @Test
    void rescanDrainsABacklogLargerThanTheQueue(@TempDir Path root) throws Exception {
        IndexStore index = new IndexStore(root);
        EventRangeDecoder decoder = new EventRangeDecoder(index, root, 1);
        try {
            for (int i = 0; i < 1000; i++) {
                String chunkId = "backlog-" + i;
                assertTrue(index.insertPending(undecoded(chunkId)));
                index.markStored(chunkId);
            }
            for (int attempt = 0; attempt < 8 && !index.undecodedChunkIds(2000).isEmpty(); attempt++) {
                decoder.rescan();
                decoder.awaitIdle(Duration.ofSeconds(30));
            }
            assertEquals(List.of(), index.undecodedChunkIds(2000));
        } finally {
            decoder.close();
            index.close();
        }
    }

    private static ChunkIndexRow undecoded(String chunkId) {
        return new ChunkIndexRow(
                chunkId,
                "rec",
                "default",
                null,
                "ns",
                "pod",
                "uid",
                "app",
                "default",
                "profile.jfr",
                null,
                null,
                0L,
                4,
                100,
                200,
                100,
                200,
                "checksum",
                "default/ns/pod/app/chunks/uid/default/" + chunkId + ".jfr",
                ChunkIndexRow.STATUS_PENDING,
                null,
                null);
    }

    private static ChunkIndexRow awaitDecoded(IndexStore index, String chunkId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        ChunkIndexRow row = null;
        while (System.nanoTime() < deadline) {
            row = index.get(chunkId);
            if (row != null && row.decodedAt() != null) {
                return row;
            }
            Thread.sleep(20);
        }
        return row;
    }

    @Name("io.jafra.RangeMarker")
    @Label("Range marker")
    @StackTrace(false)
    public static class RangeMarker extends jdk.jfr.Event {
        @Label("Token")
        public String token;
    }
}

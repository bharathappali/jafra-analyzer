package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;

class IntegrityReportTest {
    @Test
    void lagIsZeroWhenEveryChunkIsDoneAndOneWhenOneIsFailed() throws Exception {
        Path root = Files.createTempDirectory("jafra-integrity");
        try (IngestCatalog catalog = new IngestCatalog(root.resolve("v2"))) {
            ChunkStore store = new ChunkStore(root);
            store.recover();
            for (int i = 0; i < 3; i++) {
                ChunkMetadata metadata = chunk("chunk-" + i, i);
                ChunkStore.IncomingWrite incoming = store.beginWrite(metadata.chunkId());
                incoming.write(new byte[] {1, 2, 3});
                store.commit(incoming, metadata);
                catalog.enqueue(metadata);
                if (i < 2) {
                    catalog.complete(metadata.chunkId(), "jvm", metadata.recordingId(), metadata.chunkStartTimeNs(),
                            metadata.chunkStartTimeNs() + metadata.chunkDurationNs(), 4L, 4L, null);
                } else {
                    catalog.fail(metadata.chunkId(), "not yet");
                }
            }
            Map<String, Object> lagged = onlyPod(IntegrityReport.of(store, catalog.coverage()));
            assertEquals(1L, lagged.get("lag_chunks"));
            assertFalse((Boolean) lagged.get("matched"));

            catalog.complete("chunk-2", "jvm", "rec", 3L, 4L, 1L, 1L, null);
            Map<String, Object> matched = onlyPod(IntegrityReport.of(store, catalog.coverage()));
            assertEquals(0L, matched.get("lag_chunks"));
            assertTrue((Boolean) matched.get("matched"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyPod(Map<String, Object> report) {
        List<Map<String, Object>> pods = (List<Map<String, Object>>) report.get("pods");
        assertEquals(1, pods.size());
        return pods.get(0);
    }

    private static ChunkMetadata chunk(String chunkId, long start) {
        return new ChunkMetadata(chunkId, "rec", "cluster", "ns", "uid", "pod", "app", "rec", 0L, 1L, "sum", start, 1L);
    }
}

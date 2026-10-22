package io.jafra.analyzer.v2.rollup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jafra.analyzer.index.IndexStore;
import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;
import io.jafra.analyzer.v2.store.V2Store;
import jdk.jfr.Recording;

class RollupWriterTest {
    @Test
    void rollsUpARealRecording(@TempDir Path root) throws Exception {
        IndexStore index = new IndexStore(root);
        ChunkStore chunks = new ChunkStore(root, index);
        V2Store store = new V2Store(root);
        RollupWriter writer = new RollupWriter(index, store, root, 1);
        try {
            chunks.recover();
            Path dump = Files.createTempFile("jafra-v2", ".jfr");
            try (Recording recording = new Recording()) {
                recording.enable("jdk.GarbageCollection");
                recording.enable("jdk.GCHeapSummary");
                recording.enable("jdk.GCPhasePause");
                recording.enable("jdk.GCHeapConfiguration");
                recording.enable("jdk.JVMInformation");
                recording.start();
                byte[] garbage = new byte[8_000_000];
                garbage[0] = 1;
                System.gc();
                recording.stop();
                recording.dump(dump);
            }
            byte[] payload = Files.readAllBytes(dump);
            Files.deleteIfExists(dump);
            long startNs = System.currentTimeMillis() * 1_000_000L;
            ChunkMetadata metadata = new ChunkMetadata(
                    "gc-chunk",
                    "rec",
                    "cluster-a",
                    "ns",
                    "pod-uid",
                    "payments",
                    "app",
                    "profile.jfr",
                    0,
                    payload.length,
                    "checksum",
                    startNs,
                    5_000_000_000L);
            ChunkStore.IncomingWrite write = chunks.beginWrite("gc-chunk");
            write.write(payload);
            chunks.commit(write, metadata);

            writer.process("gc-chunk");
            writer.process("gc-chunk");

            assertEquals(1, store.windowCount());
            Map<String, V2Store.RollupRow> totals = totals(store.rollup5s());
            assertTrue(totals.containsKey("gc.pause.us"), totals.keySet().toString());
            assertTrue(totals.containsKey("gc.pause.time_pct"), totals.keySet().toString());
            assertTrue(totals.containsKey("heap.after_gc.bytes"), totals.keySet().toString());
            assertTrue(totals.containsKey("heap.occupancy.pct"), totals.keySet().toString());
            assertTrue(totals.get("heap.occupancy.pct").avg() >= 0d);

            boolean totalHist = false;
            long previousJvm = Long.MIN_VALUE;
            String previousMetric = "";
            String previousKey = "";
            long previousTs = Long.MIN_VALUE;
            for (V2Store.HistRow row : store.histRows()) {
                assertTrue(row.count() > 0);
                boolean ordered = row.jvmId() > previousJvm
                        || (row.jvmId() == previousJvm && row.metric().compareTo(previousMetric) > 0)
                        || (row.jvmId() == previousJvm
                                && row.metric().equals(previousMetric)
                                && row.key().compareTo(previousKey) > 0)
                        || (row.jvmId() == previousJvm
                                && row.metric().equals(previousMetric)
                                && row.key().equals(previousKey)
                                && row.ts() >= previousTs);
                assertTrue(ordered);
                previousJvm = row.jvmId();
                previousMetric = row.metric();
                previousKey = row.key();
                previousTs = row.ts();
                if ("gc.pause.us".equals(row.metric()) && row.key().isEmpty()) {
                    totalHist = true;
                }
            }
            assertTrue(totalHist);

            Map<String, V2Store.RollupRow> minute = totals(store.rollup1m());
            for (Map.Entry<String, V2Store.RollupRow> entry : totals.entrySet()) {
                V2Store.RollupRow five = entry.getValue();
                V2Store.RollupRow one = minute.get(entry.getKey());
                assertTrue(one != null, entry.getKey());
                assertEquals(five.min(), one.min(), 1e-6);
                assertEquals(five.max(), one.max(), 1e-6);
                assertEquals(five.count(), one.count());
                assertEquals(five.sum(), one.sum(), 1e-6);
            }
        } finally {
            writer.close();
            store.close();
            chunks.close();
            index.close();
        }
    }

    @Test
    void minuteRowMergesItsFiveSecondRows(@TempDir Path root) throws Exception {
        V2Store store = new V2Store(root);
        RollupWriter writer = new RollupWriter(new IndexStore(root), store, root, 1);
        try {
            writer.commitSynthetic("w1", gauge(60_000, 10));
            writer.commitSynthetic("w2", gauge(65_000, 30));
            V2Store.RollupRow minute = totals(store.rollup1m()).get("heap.used.bytes");
            assertEquals(10d, minute.min(), 1e-9);
            assertEquals(30d, minute.max(), 1e-9);
            assertEquals(2L, minute.count());
            assertEquals(40d, minute.sum(), 1e-9);
            assertEquals(20d, minute.avg(), 1e-9);
        } finally {
            writer.close();
            store.close();
        }
    }

    @Test
    void counterResetSkipsTheWindow(@TempDir Path root) throws Exception {
        V2Store store = new V2Store(root);
        RollupWriter writer = new RollupWriter(new IndexStore(root), store, root, 1);
        try {
            writer.commitSynthetic("c0", counter(0, 100));
            writer.commitSynthetic("c1", counter(5_000, 150));
            writer.commitSynthetic("c2", counter(10_000, 40));
            writer.commitSynthetic("c3", counter(15_000, 90));
            Map<Long, Double> rates = new HashMap<>();
            for (V2Store.RollupRow row : store.rollup5s()) {
                if ("exceptions.thrown.per_s".equals(row.metric()) && row.key().isEmpty()) {
                    rates.put(row.ts(), row.avg());
                }
            }
            assertFalse(rates.containsKey(0L));
            assertEquals(10d, rates.get(5_000L), 1e-9);
            assertFalse(rates.containsKey(10_000L));
            assertEquals(10d, rates.get(15_000L), 1e-9);
        } finally {
            writer.close();
            store.close();
        }
    }

    @Test
    void deletedChunkWritesNothing(@TempDir Path root) throws Exception {
        IndexStore index = new IndexStore(root);
        ChunkStore chunks = new ChunkStore(root, index);
        V2Store store = new V2Store(root);
        RollupWriter writer = new RollupWriter(index, store, root, 1);
        try {
            chunks.recover();
            byte[] payload = Files.readAllBytes(recordTiny());
            long startNs = 1_000_000_000L;
            ChunkMetadata metadata = new ChunkMetadata(
                    "gone",
                    "rec",
                    "cluster-a",
                    "ns",
                    "pod-uid",
                    "payments",
                    "app",
                    "profile.jfr",
                    0,
                    payload.length,
                    "checksum",
                    startNs,
                    5_000_000_000L);
            ChunkStore.IncomingWrite write = chunks.beginWrite("gone");
            write.write(payload);
            chunks.commit(write, metadata);
            Path payloadPath = chunks.payloadPath("gone");
            RollupWriter.beforeCommit = () -> {
                try {
                    Files.deleteIfExists(payloadPath);
                    index.delete("gone");
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            };
            writer.process("gone");
            assertEquals(0, store.windowCount());
        } finally {
            RollupWriter.beforeCommit = () -> {};
            writer.close();
            store.close();
            chunks.close();
            index.close();
        }
    }

    private static WindowData gauge(long fromMs, double value) {
        WindowData data = identity(fromMs);
        data.add("heap.used.bytes", "", "", value);
        return data;
    }

    private static WindowData counter(long fromMs, double cumulative) {
        WindowData data = identity(fromMs);
        data.counter("exceptions.thrown.per_s", "", "", cumulative);
        return data;
    }

    private static WindowData identity(long fromMs) {
        WindowData data = new WindowData();
        data.clusterId = "cluster-a";
        data.namespace = "ns";
        data.podName = "payments";
        data.podUid = "pod-uid";
        data.containerName = "app";
        data.jvmStartMs = 1_700_000_000_000L;
        data.jdkMajor = 21;
        data.jdkVersion = "21.0.2";
        data.profile = "aggressive";
        data.fromMs = fromMs;
        data.toMs = fromMs + 5_000;
        data.dtSeconds = 5d;
        return data;
    }

    private static Map<String, V2Store.RollupRow> totals(java.util.List<V2Store.RollupRow> rows) {
        Map<String, V2Store.RollupRow> totals = new HashMap<>();
        for (V2Store.RollupRow row : rows) {
            if (row.byName().isEmpty() && row.key().isEmpty()) {
                totals.putIfAbsent(row.metric(), row);
            }
        }
        return totals;
    }

    private static Path recordTiny() throws Exception {
        Path dump = Files.createTempFile("jafra-v2-tiny", ".jfr");
        try (Recording recording = new Recording()) {
            recording.start();
            recording.stop();
            recording.dump(dump);
        }
        return dump;
    }
}

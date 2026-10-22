package io.jafra.analyzer.v2.incidents;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;
import io.jafra.analyzer.v2.rollup.RollupWriter;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jdk.jfr.Recording;

@QuarkusTest
class IncidentResourceTest {
    @Inject
    RollupWriter writer;

    @Inject
    ChunkStore chunks;

    @Test
    void syntheticWindowsOpenAndSortIncidents() throws Exception {
        String pod = "pod-" + System.nanoTime();
        writer.commitSynthetic("pause-" + pod, window(pod, 0, "gc.pause.us", 500_000));
        writer.commitSynthetic("heap-a-" + pod, window(pod, 5_000, "heap.occupancy.pct", 96));
        writer.commitSynthetic("heap-b-" + pod, window(pod, 10_000, "heap.occupancy.pct", 96));

        String base = "/v2/jvms/v2inc/" + pod + "/app";
        String id = given()
                .get(base + "/incidents")
                .then()
                .statusCode(200)
                .body("incidents", hasSize(2))
                .body("truncated", equalTo(false))
                .body("incidents[0].type", equalTo("heap_near_full"))
                .body("incidents[0].level", equalTo("critical"))
                .body("incidents[0].status", equalTo("open"))
                .body("incidents[1].type", equalTo("long_pause"))
                .extract()
                .path("incidents[0].id");

        given().get(base + "/incidents/" + id)
                .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("type", equalTo("heap_near_full"));

        given().get(base + "/incidents/missing")
                .then()
                .statusCode(404)
                .body("error", equalTo("unknown_incident"))
                .body("valid", hasSize(2));
    }

    @Test
    void recordedSystemGcOpensLongPause() throws Exception {
        String pod = "pod-" + System.nanoTime();
        java.nio.file.Path dump = Files.createTempFile("jafra-v2-pause", ".jfr");
        long startMs = System.currentTimeMillis() - 1_000L;
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
        String chunkId = "gc-" + pod;
        long startNs = startMs * 1_000_000L;
        ChunkMetadata metadata = new ChunkMetadata(
                chunkId, "rec", "cluster-a", "v2inc", "uid", pod, "app", "profile.jfr", 0, payload.length, "checksum", startNs, 5_000_000_000L);
        ChunkStore.IncomingWrite write = chunks.beginWrite(chunkId);
        write.write(payload);
        chunks.commit(write, metadata);
        writer.awaitIdle(Duration.ofSeconds(10));

        Integer pauses = given()
                .queryParam("type", "long_pause")
                .get("/v2/jvms/v2inc/" + pod + "/app/incidents")
                .then()
                .statusCode(200)
                .extract()
                .path("total");
        assertEquals(1, pauses);
    }

    private static WindowData window(String pod, long from, String metric, double value) {
        WindowData data = new WindowData();
        data.clusterId = "cluster-a";
        data.namespace = "v2inc";
        data.podName = pod;
        data.podUid = "uid";
        data.containerName = "app";
        data.jvmStartMs = 11_000L;
        data.jdkMajor = 21;
        data.jdkVersion = "21.0.0";
        data.profile = "aggressive";
        data.settingsJson = "{}";
        data.fromMs = from;
        data.toMs = from + 5_000;
        data.dtSeconds = 5;
        data.add(metric, "", "", value);
        if ("gc.pause.us".equals(metric)) {
            long us = (long) value;
            data.event(new WindowData.StoredEvent(
                    "gc.pause",
                    from,
                    us,
                    "",
                    null,
                    null,
                    "{\"collector\":\"G1Young\",\"cause\":\"System.gc()\",\"gc_id\":1,\"duration_us\":" + us + "}"));
        }
        return data;
    }
}

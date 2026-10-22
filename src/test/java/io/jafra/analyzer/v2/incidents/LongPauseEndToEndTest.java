package io.jafra.analyzer.v2.incidents;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;
import io.jafra.analyzer.v2.rollup.RollupWriter;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jdk.jfr.Recording;

@QuarkusTest
class LongPauseEndToEndTest {
    @Inject
    RollupWriter writer;

    @Inject
    ChunkStore chunks;

    @Test
    void recordedSystemGcOpensLongPause() throws Exception {
        String pod = "pod-" + System.nanoTime();
        java.nio.file.Path dump = Files.createTempFile("jafra-v2-long-pause", ".jfr");
        long startMs = System.currentTimeMillis() - 1_000L;
        try (Recording recording = new Recording()) {
            recording.enable("jdk.GarbageCollection");
            recording.enable("jdk.GCHeapSummary");
            recording.enable("jdk.GCPhasePause");
            recording.enable("jdk.GCPhasePauseLevel1");
            recording.enable("jdk.GCHeapConfiguration");
            recording.enable("jdk.JVMInformation");
            recording.enable("jdk.SystemGC");
            recording.start();
            System.gc();
            recording.stop();
            recording.dump(dump);
        }
        byte[] payload = Files.readAllBytes(dump);
        Files.deleteIfExists(dump);
        String chunkId = "gc-" + pod;
        long startNs = startMs * 1_000_000L;
        ChunkMetadata metadata = new ChunkMetadata(
                chunkId,
                "rec",
                "cluster-a",
                "v2inc",
                "uid",
                pod,
                "app",
                "profile.jfr",
                0,
                payload.length,
                "checksum",
                startNs,
                5_000_000_000L);
        ChunkStore.IncomingWrite write = chunks.beginWrite(chunkId);
        write.write(payload);
        chunks.commit(write, metadata);
        writer.awaitIdle(Duration.ofSeconds(10));

        String base = "/v2/jvms/v2inc/" + pod + "/app";
        String id = given()
                .queryParam("type", "long_pause")
                .get(base + "/incidents")
                .then()
                .statusCode(200)
                .body("total", equalTo(1))
                .extract()
                .path("incidents[0].id");
        JsonPath body = given().get(base + "/incidents/" + id).then().statusCode(200).extract().jsonPath();
        assertTrue(body.getString("headline").contains("System.gc()"));
        assertEquals("site", body.getString("who.kind"));
        assertTrue(body.getList("where").size() <= 5);
        List<String> todo = body.getList("todo");
        assertTrue(todo.size() >= 1 && todo.size() <= 3, todo.toString());
        for (String line : todo) {
            assertFalse(line == null || line.isBlank(), todo.toString());
        }
        assertTrue(body.getList("sources.events").contains("jdk.GarbageCollection"));
        assertNotNull(body.get("how_bad.evidence.worst.band"));
        assertEquals("A", body.getString("how_bad.evidence.worst.family"));
        assertFalse(body.getString("how_bad.evidence.worst.reason").isBlank());
    }
}

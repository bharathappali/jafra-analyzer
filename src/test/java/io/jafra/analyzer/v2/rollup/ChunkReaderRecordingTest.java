package io.jafra.analyzer.v2.rollup;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;
import io.jafra.analyzer.v2.api.V2Times;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jdk.jfr.Recording;

@QuarkusTest
class ChunkReaderRecordingTest {
    @Inject
    RollupWriter writer;

    @Inject
    ChunkStore chunks;

    @Test
    void recordedJvmStoresTheRunningFeatureVersion() throws Exception {
        String pod = "pod-" + System.nanoTime();
        ingest(pod, "jdk-yes-" + pod, true);
        int feature = Runtime.version().feature();

        JsonPath workloads = given()
                .queryParam("cluster", "cluster-a")
                .get("/v2/workloads")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        List<Map<String, Object>> rows = workloads.getList("findAll { it.pod == '" + pod + "' }");
        assertEquals(1, rows.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> jvm = ((List<Map<String, Object>>) rows.get(0).get("jvms")).get(0);
        assertEquals(feature, ((Number) jvm.get("jdk")).intValue());

        List<List<String>> flags = given()
                .queryParam("from", "0")
                .queryParam("to", String.valueOf(System.currentTimeMillis() + 60_000L))
                .get("/v2/jvms/v2jdk/" + pod + "/app/report")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("groups.flags");
        assertFalse(flags.isEmpty());
        assertTrue(flags.stream().anyMatch(group -> group == null || !group.contains("not_on_this_jdk")));
    }

    @Test
    void chunkWithoutJvmInformationReusesTheStoredRow() throws Exception {
        String pod = "pod-" + System.nanoTime();
        WindowData known = new WindowData();
        known.clusterId = "cluster-a";
        known.namespace = "v2jdk";
        known.podName = pod;
        known.podUid = "uid";
        known.containerName = "app";
        known.jvmStartMs = 4_000L;
        known.jdkMajor = 21;
        known.jdkVersion = "21.0.12";
        known.profile = "custom";
        known.settingsJson = "{}";
        known.fromMs = 0L;
        known.toMs = 5_000L;
        known.dtSeconds = 5;
        writer.commitSynthetic("jdk-known-" + pod, known);

        ingest(pod, "jdk-no-" + pod, false);

        JsonPath workloads = given()
                .queryParam("cluster", "cluster-a")
                .get("/v2/workloads")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        List<Map<String, Object>> rows = workloads.getList("findAll { it.pod == '" + pod + "' }");
        assertEquals(1, rows.size(), rows.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> jvm = ((List<Map<String, Object>>) rows.get(0).get("jvms")).get(0);
        assertEquals(21, ((Number) jvm.get("jdk")).intValue());
        assertEquals(V2Times.text(4_000L), jvm.get("start"));
    }

    private void ingest(String pod, String chunkId, boolean jvmInformation) throws Exception {
        java.nio.file.Path dump = Files.createTempFile("jafra-jdk", ".jfr");
        long startMs = System.currentTimeMillis() - 1_000L;
        try (Recording recording = new Recording()) {
            if (jvmInformation) {
                recording.enable("jdk.JVMInformation");
            }
            recording.enable("jdk.CPULoad");
            recording.start();
            recording.stop();
            recording.dump(dump);
        }
        byte[] payload = Files.readAllBytes(dump);
        Files.deleteIfExists(dump);
        ChunkMetadata metadata = new ChunkMetadata(
                chunkId,
                "rec",
                "cluster-a",
                "v2jdk",
                "uid",
                pod,
                "app",
                "profile.jfr",
                0,
                payload.length,
                "checksum",
                startMs * 1_000_000L,
                5_000_000_000L);
        ChunkStore.IncomingWrite write = chunks.beginWrite(chunkId);
        write.write(payload);
        chunks.commit(write, metadata);
        writer.awaitIdle(Duration.ofSeconds(15));
    }
}

package io.jafra.analyzer.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

@QuarkusTest
class RecordingResourceTest {
    @Inject
    ChunkStore store;

    @Test
    void listsAndReportsByNamespacePodAndContainer() throws Exception {
        byte[] recording = dumpRecording();
        persist("api-chunk", "api-test", "api-pod", "api-container", "profile-3.jfr", recording);

        given().when()
                .queryParam("namespace", "api-test")
                .get("/api/v1/recordings")
                .then()
                .statusCode(200)
                .body("workloads", hasSize(1))
                .body("workloads[0].namespace", equalTo("api-test"))
                .body("workloads[0].pod", equalTo("api-pod"))
                .body("workloads[0].container", equalTo("api-container"))
                .body("workloads[0].recordings[0].filename", equalTo("profile-3.jfr"))
                .body("workloads[0].recordings[0].start", notNullValue())
                .body("workloads[0].recordings[0].end", notNullValue());

        given().when()
                .get("/api/v1/namespaces/api-test/pods/api-pod/containers/api-container")
                .then()
                .statusCode(200)
                .body("recordings", hasSize(1));

        Map<String, Object> report = given().when()
                .get("/api/v1/namespaces/api-test/pods/api-pod/containers/api-container/report")
                .then()
                .statusCode(200)
                .body("namespace", equalTo("api-test"))
                .body("pod", equalTo("api-pod"))
                .body("container", equalTo("api-container"))
                .body("recording", equalTo("profile-3.jfr"))
                .extract()
                .jsonPath()
                .getMap("findings");
        assertFalse(report.isEmpty());

        given().when()
                .queryParam("filter", "heap")
                .get("/api/v1/namespaces/api-test/pods/api-pod/containers/api-container/recordings/profile-3.jfr/report")
                .then()
                .statusCode(200)
                .body("findings.size()", greaterThan(0));
    }

    @Test
    void summaryReturnsRawEventAggregatesAndAcceptsTimeWindows() throws Exception {
        persist("sum-1", "sum-test", "sum-pod", "sum-container", "profile-1.jfr", dumpBusyRecording());
        persist("sum-2", "sum-test", "sum-pod", "sum-container", "profile-2.jfr", dumpBusyRecording());

        given().when()
                .get("/api/v1/namespaces/sum-test/pods/sum-pod/containers/sum-container/summary")
                .then()
                .statusCode(200)
                .body("namespace", equalTo("sum-test"))
                .body("events.size()", greaterThan(0))
                .body("topics.size()", greaterThan(0))
                .body("topics.cpu.stats.CPU_TYPE", equalTo(null))
                .body("topics.general.stats.BASE_ADDRESS", equalTo(null))
                .body("start", notNullValue())
                .body("end", notNullValue());

        given().when()
                .queryParam("last", "1h")
                .get("/api/v1/namespaces/sum-test/pods/sum-pod/containers/sum-container/summary")
                .then()
                .statusCode(200)
                .body("recordings", contains("profile-1.jfr", "profile-2.jfr"))
                .body("events.size()", greaterThan(0))
                .body("from", notNullValue())
                .body("to", notNullValue());

        given().when()
                .queryParam("filter", "heap")
                .get("/api/v1/namespaces/sum-test/pods/sum-pod/containers/sum-container/recordings/profile-1.jfr/summary")
                .then()
                .statusCode(200);

        given().when()
                .queryParam("last", "nope")
                .get("/api/v1/namespaces/sum-test/pods/sum-pod/containers/sum-container/summary")
                .then()
                .statusCode(400);
    }

    @Test
    void windowedReportMergesRecordingsAndRejectsBadQueries() throws Exception {
        persist("win-1", "win-test", "win-pod", "win-container", "profile-1.jfr", dumpRecording());
        persist("win-2", "win-test", "win-pod", "win-container", "profile-2.jfr", dumpRecording());

        given().when()
                .queryParam("last", "1h")
                .get("/api/v1/namespaces/win-test/pods/win-pod/containers/win-container/report")
                .then()
                .statusCode(200)
                .body("recordings", contains("profile-1.jfr", "profile-2.jfr"))
                .body("start", notNullValue())
                .body("end", notNullValue())
                .body("from", notNullValue())
                .body("to", notNullValue())
                .body("findings.size()", greaterThan(0));

        given().when()
                .queryParam("from", "1990-01-01T00:00:00Z")
                .queryParam("to", "1990-01-02T00:00:00Z")
                .get("/api/v1/namespaces/win-test/pods/win-pod/containers/win-container/report")
                .then()
                .statusCode(404)
                .body("status", equalTo("not_found"));

        given().when()
                .queryParam("last", "nope")
                .get("/api/v1/namespaces/win-test/pods/win-pod/containers/win-container/report")
                .then()
                .statusCode(400)
                .body("status", equalTo("bad_request"));

        given().when()
                .queryParam("last", "5m")
                .queryParam("from", "2026-08-17T09:00:00Z")
                .get("/api/v1/namespaces/win-test/pods/win-pod/containers/win-container/report")
                .then()
                .statusCode(400);
    }

    @Test
    void missingWorkloadIsNotFound() {
        given().when()
                .get("/api/v1/namespaces/missing/pods/none/containers/none/report")
                .then()
                .statusCode(anyOf(equalTo(404)));
    }

    private void persist(String chunkId, String namespace, String pod, String container, String filename, byte[] payload)
            throws Exception {
        String recordingId = "local-demo/" + pod + "/" + container + "/" + filename;
        ChunkMetadata metadata = new ChunkMetadata(
                chunkId,
                recordingId,
                "local-demo",
                namespace,
                "uid-" + pod,
                pod,
                container,
                filename,
                0,
                payload.length,
                "checksum");
        ChunkStore.IncomingWrite write = store.beginWrite(chunkId);
        write.write(payload);
        store.commit(write, metadata);
    }

    private static byte[] dumpRecording() throws Exception {
        Path dump = Files.createTempFile("jafra-report", ".jfr");
        try (Recording recording = new Recording()) {
            recording.start();
            Thread.sleep(300);
            recording.dump(dump);
            return Files.readAllBytes(dump);
        } finally {
            Files.deleteIfExists(dump);
        }
    }

    private static byte[] dumpBusyRecording() throws Exception {
        Path dump = Files.createTempFile("jafra-summary", ".jfr");
        try (Recording recording = new Recording(Configuration.getConfiguration("default"))) {
            recording.start();
            byte[] scratch = new byte[256];
            long until = System.nanoTime() + 200_000_000L;
            while (System.nanoTime() < until) {
                scratch = new byte[256 + (scratch.length % 32)];
            }
            recording.dump(dump);
            return Files.readAllBytes(dump);
        } finally {
            Files.deleteIfExists(dump);
        }
    }
}

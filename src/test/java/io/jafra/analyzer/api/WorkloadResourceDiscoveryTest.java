package io.jafra.analyzer.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jafra.analyzer.recordings.WorkloadIndex;
import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class WorkloadResourceDiscoveryTest {
    @Inject
    ChunkStore store;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WorkloadIndex workloadIndex;

    @Test
    void discoveryTreeWithoutFilters() throws Exception {
        persist("disc-1", "ns-one", "pod-a", "app", "profile-0.jfr");
        persist("disc-2", "ns-two", "pod-b", "worker", "profile-0.jfr");

        var indexed = workloadIndex.discover(null, null, null)
                .namespaces()
                .get("ns-one")
                .pods()
                .get("pod-a")
                .containers()
                .get("app");
        assertTrue(indexed.recordingCount() >= 1, "in-memory index should count recordings");

        JsonNode root = objectMapper.readTree(given().when().get("/api/v1/workload").then().statusCode(200).extract().asString());
        JsonNode app = container(root, "ns-one", "pod-a", "app");
        JsonNode worker = container(root, "ns-two", "pod-b", "worker");
        assertTrue(
                app.path("recordingCount").asInt() >= 1,
                () -> "HTTP workload tree missing stats: " + root.toPrettyString());
        assertTrue(worker.path("chunkCount").asInt() >= 1);
    }

    @Test
    void discoveryByNamespaceAndInvertedPodSearch() throws Exception {
        persist("disc-3", "ns-a", "shared-pod", "c1", "profile-0.jfr");
        persist("disc-4", "ns-b", "shared-pod", "c2", "profile-0.jfr");

        JsonNode scoped = objectMapper.readTree(given().when()
                .queryParam("namespace", "ns-a")
                .get("/api/v1/workload")
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertEquals("ns-a", scoped.path("namespace").asText());
        assertTrue(scoped.path("pods").path("shared-pod").path("containers").path("c1").path("recordingCount").asInt() >= 1);

        JsonNode inverted = objectMapper.readTree(given().when()
                .queryParam("pod", "shared-pod")
                .get("/api/v1/workload")
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertTrue(container(inverted, "ns-a", "shared-pod", "c1").path("chunkCount").asInt() >= 1);
        assertTrue(container(inverted, "ns-b", "shared-pod", "c2").path("chunkCount").asInt() >= 1);
    }

    @Test
    void fullWorkloadReturnsDetailWithStats() throws Exception {
        persist("disc-5", "detail-ns", "detail-pod", "detail-c", "profile-3.jfr");

        given().when()
                .queryParam("namespace", "detail-ns")
                .queryParam("pod", "detail-pod")
                .queryParam("container", "detail-c")
                .get("/api/v1/workload")
                .then()
                .statusCode(200)
                .body("namespace", equalTo("detail-ns"))
                .body("recordingCount", greaterThanOrEqualTo(1))
                .body("chunkCount", greaterThanOrEqualTo(1))
                .body("recordings[0].filename", equalTo("profile-3.jfr"));
    }

    private static JsonNode container(JsonNode root, String namespace, String pod, String container) {
        return root.path("namespaces")
                .path(namespace)
                .path("pods")
                .path(pod)
                .path("containers")
                .path(container);
    }

    private void persist(String chunkId, String namespace, String pod, String container, String filename)
            throws Exception {
        ChunkMetadata metadata = new ChunkMetadata(
                chunkId,
                "rec/" + chunkId,
                "cluster",
                namespace,
                "pod-uid-" + chunkId,
                pod,
                container,
                filename,
                0,
                4,
                "checksum",
                1_700_000_000_000_000_000L,
                60_000_000_000L);
        ChunkStore.IncomingWrite write = store.beginWrite(chunkId);
        write.write(new byte[] {1, 2, 3, 4});
        store.commit(write, metadata);
    }
}

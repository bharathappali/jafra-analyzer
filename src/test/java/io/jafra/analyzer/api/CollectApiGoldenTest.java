package io.jafra.analyzer.api;

import static io.jafra.analyzer.test.GoldenWorkloadQuery.workloadWindow;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkStore;
import io.jafra.analyzer.test.ChunkFixtureLoader;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class CollectApiGoldenTest {
    @Inject
    ChunkStore store;

    @BeforeEach
    void loadGoldenChunks() throws Exception {
        ChunkFixtureLoader.loadAll(store);
    }

    @Test
    void eventTypesListsJvmAndFlagEvents() {
        workloadWindow().when()
                .get("/api/v1/event-types")
                .then()
                .statusCode(200)
                .body("context.namespace", equalTo("default"))
                .body("types.id", hasItem("jdk.JVMInformation"))
                .body("types.id", hasItem("jdk.BooleanFlag"))
                .body("types.size()", greaterThan(10));
    }

    @Test
    void recordingQueryParamIsRejected() {
        workloadWindow().queryParam("recording", "profile-223.jfr").when()
                .get("/api/v1/event-types")
                .then()
                .statusCode(400)
                .body("error", equalTo("recording is not supported; use last, from/to, before, or after"));
    }

    @Test
    void jvmEndpointReturnsJvmInformation() {
        workloadWindow().when()
                .get("/api/v1/jvm")
                .then()
                .statusCode(200)
                .body("jvm.jvmVersion", notNullValue());
    }

    @Test
    void jvmFlagsExposeHeapAndGcSelectors() {
        workloadWindow().when()
                .get("/api/v1/jvm/flags")
                .then()
                .statusCode(200)
                .body("flags.MaxHeapSize", notNullValue())
                .body("flags.UseG1GC", notNullValue());
    }

    @Test
    void eventsSummaryForJvmInformation() {
        workloadWindow()
                .queryParam("types", "JVMInformation")
                .queryParam("view", "summary")
                .when()
                .get("/api/v1/events")
                .then()
                .statusCode(200)
                .body("summary.'jdk.JVMInformation'.count", greaterThan(0));
    }
}

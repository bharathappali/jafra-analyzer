package io.jafra.analyzer.api;

import static io.restassured.RestAssured.given;
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
        given().when()
                .queryParam("namespace", "default")
                .queryParam("pod", "auth-cache-84cd9fbc49-fttcq")
                .queryParam("container", "auth-cache")
                .queryParam("recording", "profile-223.jfr")
                .get("/api/v1/event-types")
                .then()
                .statusCode(200)
                .body("context.namespace", org.hamcrest.Matchers.equalTo("default"))
                .body("types.id", hasItem("jdk.JVMInformation"))
                .body("types.id", hasItem("jdk.BooleanFlag"))
                .body("types.size()", greaterThan(10));
    }

    @Test
    void jvmEndpointReturnsJvmInformation() {
        given().when()
                .queryParam("namespace", "default")
                .queryParam("pod", "auth-cache-84cd9fbc49-fttcq")
                .queryParam("container", "auth-cache")
                .queryParam("recording", "profile-223.jfr")
                .get("/api/v1/jvm")
                .then()
                .statusCode(200)
                .body("jvm.jvmVersion", notNullValue());
    }

    @Test
    void jvmFlagsExposeHeapAndGcSelectors() {
        given().when()
                .queryParam("namespace", "default")
                .queryParam("pod", "auth-cache-84cd9fbc49-fttcq")
                .queryParam("container", "auth-cache")
                .queryParam("recording", "profile-223.jfr")
                .get("/api/v1/jvm/flags")
                .then()
                .statusCode(200)
                .body("flags.MaxHeapSize", notNullValue())
                .body("flags.UseG1GC", notNullValue());
    }

    @Test
    void eventsSummaryForJvmInformation() {
        given().when()
                .queryParam("namespace", "default")
                .queryParam("pod", "auth-cache-84cd9fbc49-fttcq")
                .queryParam("container", "auth-cache")
                .queryParam("recording", "profile-223.jfr")
                .queryParam("types", "JVMInformation")
                .queryParam("view", "summary")
                .get("/api/v1/events")
                .then()
                .statusCode(200)
                .body("summary.'jdk.JVMInformation'.count", greaterThan(0));
    }
}

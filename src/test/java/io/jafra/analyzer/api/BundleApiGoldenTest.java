package io.jafra.analyzer.api;

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
class BundleApiGoldenTest {
    @Inject
    ChunkStore store;

    @BeforeEach
    void loadGoldenChunks() throws Exception {
        ChunkFixtureLoader.loadAll(store);
    }

    @Test
    void catalogListsPlaybookBundles() {
        given().when()
                .get("/api/v1/bundles")
                .then()
                .statusCode(200)
                .body("bundles.id", hasItem("gc"))
                .body("bundles.id", hasItem("memory"))
                .body("bundles.id", hasItem("version-check"));
    }

    @Test
    void versionCheckBundleReturnsJvmSection() {
        given().when()
                .queryParam("namespace", "default")
                .queryParam("pod", "auth-cache-84cd9fbc49-fttcq")
                .queryParam("container", "auth-cache")
                .queryParam("recording", "profile-223.jfr")
                .get("/api/v1/bundles/version-check")
                .then()
                .statusCode(200)
                .body("id", equalTo("version-check"))
                .body("sections.jvm.jvmVersion", notNullValue());
    }

    @Test
    void gcBundleComposesPauseAndTrendSections() {
        given().when()
                .queryParam("namespace", "default")
                .queryParam("pod", "auth-cache-84cd9fbc49-fttcq")
                .queryParam("container", "auth-cache")
                .queryParam("recording", "profile-223.jfr")
                .get("/api/v1/bundles/gc")
                .then()
                .statusCode(200)
                .body("id", equalTo("gc"))
                .body("sections.gcPauses.stats.count", greaterThan(0))
                .body("sections.trends.trends.name", hasItem("jdk.GCHeapSummary"));
    }

    @Test
    void performanceBundleOnMultiChunkRecording() {
        given().when()
                .queryParam("namespace", "default")
                .queryParam("pod", "auth-cache-84cd9fbc49-fttcq")
                .queryParam("container", "auth-cache")
                .queryParam("recording", "profile-607.jfr")
                .queryParam("limit", 5)
                .get("/api/v1/bundles/performance")
                .then()
                .statusCode(200)
                .body("sections.samples.stats.sampleCount", greaterThan(0))
                .body("sections.allocations.top.size()", greaterThan(0));
    }

    @Test
    void unknownBundleIsBadRequest() {
        given().when()
                .queryParam("namespace", "default")
                .queryParam("pod", "auth-cache-84cd9fbc49-fttcq")
                .queryParam("container", "auth-cache")
                .get("/api/v1/bundles/not-defined")
                .then()
                .statusCode(400);
    }
}

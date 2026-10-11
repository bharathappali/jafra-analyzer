package io.jafra.analyzer.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkStore;
import io.jafra.analyzer.test.ChunkFixtureLoader;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class AggregationApiGoldenTest {
    @Inject
    ChunkStore store;

    @BeforeEach
    void loadGoldenChunks() throws Exception {
        ChunkFixtureLoader.loadAll(store);
    }

    @Test
    void gcPausesExposePauseStats() {
        given().when()
                .queryParam("namespace", "default")
                .queryParam("pod", "auth-cache-84cd9fbc49-fttcq")
                .queryParam("container", "auth-cache")
                .queryParam("recording", "profile-223.jfr")
                .get("/api/v1/aggregations/gc-pauses")
                .then()
                .statusCode(200)
                .body("kind", equalTo("gc-pauses"))
                .body("stats.count", greaterThan(0))
                .body("stats.maxMs", greaterThan(0.0f));
    }

    @Test
    void trendsIncludeHeapSeries() {
        given().when()
                .queryParam("namespace", "default")
                .queryParam("pod", "auth-cache-84cd9fbc49-fttcq")
                .queryParam("container", "auth-cache")
                .queryParam("recording", "profile-223.jfr")
                .get("/api/v1/aggregations/trends")
                .then()
                .statusCode(200)
                .body("kind", equalTo("trends"))
                .body("trends.name", org.hamcrest.Matchers.hasItem("jdk.GCHeapSummary"))
                .body("trends.find { it.name == 'jdk.GCHeapSummary' }.points.size()", greaterThan(0));
    }

    @Test
    void allocationsRankClassesAndStacks() {
        given().when()
                .queryParam("namespace", "default")
                .queryParam("pod", "auth-cache-84cd9fbc49-fttcq")
                .queryParam("container", "auth-cache")
                .queryParam("recording", "profile-607.jfr")
                .queryParam("limit", 5)
                .get("/api/v1/aggregations/allocations")
                .then()
                .statusCode(200)
                .body("kind", equalTo("allocations"))
                .body("top.size()", greaterThan(0));
    }

    @Test
    void samplesRankExecutionStacks() {
        given().when()
                .queryParam("namespace", "default")
                .queryParam("pod", "auth-cache-84cd9fbc49-fttcq")
                .queryParam("container", "auth-cache")
                .queryParam("recording", "profile-607.jfr")
                .queryParam("flavor", "cpu")
                .queryParam("limit", 5)
                .get("/api/v1/aggregations/samples")
                .then()
                .statusCode(200)
                .body("kind", equalTo("samples"))
                .body("stats.sampleCount", greaterThan(0))
                .body("top.find { it.group == 'stack' }.stack", notNullValue());
    }

    @Test
    void invalidKindIsBadRequest() {
        given().when()
                .queryParam("namespace", "default")
                .queryParam("pod", "auth-cache-84cd9fbc49-fttcq")
                .queryParam("container", "auth-cache")
                .queryParam("recording", "profile-223.jfr")
                .get("/api/v1/aggregations/not-a-kind")
                .then()
                .statusCode(400);
    }
}

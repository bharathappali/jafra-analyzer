package io.jafra.analyzer.api;

import static io.jafra.analyzer.test.GoldenWorkloadQuery.workloadWindow;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

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
        workloadWindow().when()
                .get("/api/v1/aggregations/gc-pauses")
                .then()
                .statusCode(200)
                .header("Deprecation", equalTo(V1DeprecationFilter.DEPRECATION))
                .header("Link", containsString("/v2/events"))
                .header("Link", containsString("successor-version"))
                .body("kind", equalTo("gc-pauses"))
                .body("stats.count", greaterThan(0))
                .body("stats.maxMs", greaterThan(0.0f));
    }

    @Test
    void trendsIncludeHeapSeries() {
        workloadWindow().when()
                .get("/api/v1/aggregations/trends")
                .then()
                .statusCode(200)
                .body("kind", equalTo("trends"))
                .body("trends.name", org.hamcrest.Matchers.hasItem("jdk.GCHeapSummary"))
                .body("trends.find { it.name == 'jdk.GCHeapSummary' }.points.size()", greaterThan(0));
    }

    @Test
    void allocationsRankClassesAndStacks() {
        workloadWindow()
                .queryParam("limit", 5)
                .when()
                .get("/api/v1/aggregations/allocations")
                .then()
                .statusCode(200)
                .body("kind", equalTo("allocations"))
                .body("top.size()", greaterThan(0));
    }

    @Test
    void samplesRankExecutionStacks() {
        workloadWindow()
                .queryParam("flavor", "cpu")
                .queryParam("limit", 5)
                .when()
                .get("/api/v1/aggregations/samples")
                .then()
                .statusCode(200)
                .body("kind", equalTo("samples"))
                .body("stats.sampleCount", greaterThan(0))
                .body("top.find { it.group == 'stack' }.stack", notNullValue());
    }

    @Test
    void samplesCanReturnStackIds() {
        workloadWindow()
                .queryParam("flavor", "cpu")
                .queryParam("limit", 5)
                .queryParam("stacks", "ids")
                .when()
                .get("/api/v1/aggregations/samples")
                .then()
                .statusCode(200)
                .body("top.find { it.group == 'stack' }.stack", nullValue())
                .body("top.find { it.group == 'stack' }.stackId", notNullValue())
                .body("top.find { it.group == 'stack' }.leaf", notNullValue())
                .body("top.find { it.group == 'stack' }.topAppFrame", notNullValue())
                .body("top.find { it.group == 'stack' }.depth", notNullValue());
    }

    @Test
    void invalidKindIsBadRequest() {
        workloadWindow().when()
                .get("/api/v1/aggregations/not-a-kind")
                .then()
                .statusCode(400);
    }
}

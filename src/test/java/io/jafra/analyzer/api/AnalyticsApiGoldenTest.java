package io.jafra.analyzer.api;

import static io.jafra.analyzer.test.GoldenWorkloadQuery.workloadWindow;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkStore;
import io.jafra.analyzer.test.ChunkFixtureLoader;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class AnalyticsApiGoldenTest {
    @Inject
    ChunkStore store;

    @BeforeEach
    void loadGoldenChunks() throws Exception {
        ChunkFixtureLoader.loadAll(store);
    }

    @Test
    void timeseriesReturnsSubsystemsAndCorrelations() {
        workloadWindow().when()
                .get("/api/v1/analytics/timeseries")
                .then()
                .statusCode(200)
                .header("Deprecation", equalTo(V1DeprecationFilter.DEPRECATION))
                .header("Link", containsString("/v2/series"))
                .body("schemaVersion", equalTo(1))
                .body("mode", equalTo("timeseries"))
                .body("subsystems", hasKey("cpu"))
                .body("subsystems", hasKey("wall_time"))
                .body("subsystems", hasKey("container"))
                .body("correlations.cpuVsWall.status", notNullValue())
                .body("correlations.allocVsGc", not(hasKey("note")))
                .body("correlations.jvmVsMachineCpu", not(hasKey("note")))
                .body("correlations.throttleVsLatencyProxy", not(hasKey("note")))
                .body("correlations.contentionVsWall", not(hasKey("note")))
                .body("correlations.exceptionsVsGc", not(hasKey("note")))
                .body("correlations.ioVsBlockedThreads", not(hasKey("note")))
                .body("correlations.throttleVsRunnableWithoutCpu", not(hasKey("note")))
                .body("dataQuality.chunkCount", greaterThan(0));
    }

    @Test
    void stackIdsRoundTrip() {
        String stackId = workloadWindow()
                .queryParam("stacks", "ids")
                .when()
                .get("/api/v1/analytics/timeseries")
                .then()
                .statusCode(200)
                .body("subsystems.cpu.evidence.topStacks[0].stack", equalTo(null))
                .body("subsystems.cpu.evidence.topStacks[0].stackId", notNullValue())
                .extract()
                .path("subsystems.cpu.evidence.topStacks[0].stackId");

        workloadWindow()
                .when()
                .get("/api/v1/analytics/timeseries")
                .then()
                .statusCode(200)
                .body("subsystems.cpu.evidence.topStacks[0].stack", notNullValue());

        io.restassured.RestAssured.given()
                .when()
                .get("/api/v1/stacks/" + stackId)
                .then()
                .statusCode(200)
                .body("stackId", equalTo(stackId))
                .body("frames.size()", greaterThan(0));

        io.restassured.RestAssured.given()
                .queryParam("depth", 1)
                .when()
                .get("/api/v1/stacks/" + stackId)
                .then()
                .statusCode(200)
                .body("frames.size()", equalTo(1));

        io.restassured.RestAssured.given()
                .when()
                .get("/api/v1/stacks/0000000000000000")
                .then()
                .statusCode(404);

        io.restassured.RestAssured.given()
                .queryParam("depth", 0)
                .when()
                .get("/api/v1/stacks/" + stackId)
                .then()
                .statusCode(400);
    }

    @Test
    void profilerWindowHasWallTimePresent() {
        workloadWindow()
                .queryParam("from", "2026-09-12T22:50:54Z")
                .queryParam("to", "2026-09-12T22:51:00Z")
                .when()
                .get("/api/v1/analytics/timeseries?subsystem=wall_time,cpu")
                .then()
                .statusCode(200)
                .body("subsystems.wall_time.availability.status", equalTo("present"))
                .body("subsystems.wall_time.rollup.wallSampleCount", greaterThan(0));
    }

    @Test
    void dailyStatsMode() {
        workloadWindow()
                .when()
                .get("/api/v1/analytics/daily_stats")
                .then()
                .statusCode(200)
                .header("Link", containsString("/v2/overview"))
                .body("mode", equalTo("daily_stats"))
                .body("subsystems.gc", hasKey("rollup"))
                .body("subsystems.gc.series", equalTo(null))
                .body("correlations.allocVsGc.status", equalTo("insufficient_data"))
                .body("correlations.allocVsGc", not(hasKey("note")))
                .body("correlations.jvmVsMachineCpu", not(hasKey("note")))
                .body("correlations.throttleVsRunnableWithoutCpu", not(hasKey("note")));
    }
}

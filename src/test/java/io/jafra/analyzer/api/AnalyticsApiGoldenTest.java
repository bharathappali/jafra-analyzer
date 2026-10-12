package io.jafra.analyzer.api;

import static io.jafra.analyzer.test.GoldenWorkloadQuery.workloadWindow;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasKey;
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
                .body("schemaVersion", equalTo(1))
                .body("mode", equalTo("timeseries"))
                .body("subsystems", hasKey("cpu"))
                .body("subsystems", hasKey("wall_time"))
                .body("subsystems", hasKey("container"))
                .body("correlations.cpuVsWall.status", notNullValue())
                .body("dataQuality.chunkCount", greaterThan(0));
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
                .body("mode", equalTo("daily_stats"))
                .body("subsystems.gc", hasKey("rollup"))
                .body("subsystems.gc.series", equalTo(null));
    }
}

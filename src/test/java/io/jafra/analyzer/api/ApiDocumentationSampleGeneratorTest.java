package io.jafra.analyzer.api;

import static io.restassured.RestAssured.given;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkStore;
import io.jafra.analyzer.test.ChunkFixtureLoader;
import io.jafra.analyzer.test.GoldenWorkloadQuery;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Generates {@code docs/api/v1/samples/*.json} from golden JFR chunks. Run manually when APIs change:
 * {@code mvn -Dtest=ApiDocumentationSampleGeneratorTest test}
 */
@QuarkusTest
class ApiDocumentationSampleGeneratorTest {
    private static final Path OUT =
            Path.of("docs/api/v1/samples");

    @Inject
    ChunkStore store;

    @BeforeEach
    void loadGoldenChunks() throws Exception {
        ChunkFixtureLoader.loadAll(store);
    }

    @Test
    void writeSamples() throws Exception {
        Files.createDirectories(OUT);
        GoldenWorkloadQuery.workloadWindow();
        capture("workload-index", GoldenWorkloadQuery.workloadWindow().get("/api/v1/workload"));
        capture(
                "workload-detail",
                given()
                        .queryParam("namespace", GoldenWorkloadQuery.NAMESPACE)
                        .queryParam("pod", GoldenWorkloadQuery.POD)
                        .queryParam("container", GoldenWorkloadQuery.CONTAINER)
                        .get("/api/v1/workload"));
        capture("recordings-list", GoldenWorkloadQuery.workloadWindow().get("/api/v1/recordings"));
        capture("status", given().get("/api/v1/status"));
        capture("health", given().get("/health"));
        capture("event-types", GoldenWorkloadQuery.workloadWindow().get("/api/v1/event-types"));
        capture("bundles-catalog", given().get("/api/v1/bundles"));
        capture(
                "summary-heap-filter",
                GoldenWorkloadQuery.workloadWindow().queryParam("filter", "heap").get("/api/v1/summary"));
        capture("summary-full", GoldenWorkloadQuery.workloadWindow().get("/api/v1/summary"));
        capture(
                "report-filter-gc",
                GoldenWorkloadQuery.workloadWindow().queryParam("filter", "gc").get("/api/v1/report"));
        capture("jvm", GoldenWorkloadQuery.workloadWindow().get("/api/v1/jvm"));
        capture(
                "jvm-flags",
                GoldenWorkloadQuery.workloadWindow()
                        .queryParam("names", "MaxHeapSize,UseG1GC")
                        .get("/api/v1/jvm/flags"));
        capture(
                "events-jvm-summary",
                GoldenWorkloadQuery.workloadWindow()
                        .queryParam("types", "JVMInformation")
                        .queryParam("view", "summary")
                        .get("/api/v1/events"));
        capture(
                "events-cpu-series",
                GoldenWorkloadQuery.workloadWindow()
                        .queryParam("types", "jdk.CPULoad")
                        .queryParam("view", "series")
                        .queryParam("field", "machineTotal")
                        .queryParam("limit", "10")
                        .get("/api/v1/events"));
        capture(
                "events-wall-records",
                GoldenWorkloadQuery.workloadWindow()
                        .queryParam("from", "2026-09-12T22:50:54Z")
                        .queryParam("to", "2026-09-12T22:51:00Z")
                        .queryParam("types", "profiler.WallClockSample")
                        .queryParam("view", "records")
                        .queryParam("limit", "3")
                        .get("/api/v1/events"));
        capture("aggregation-gc-pauses", GoldenWorkloadQuery.workloadWindow().get("/api/v1/aggregations/gc-pauses"));
        capture("aggregation-trends", GoldenWorkloadQuery.workloadWindow().get("/api/v1/aggregations/trends"));
        capture(
                "aggregation-allocations",
                GoldenWorkloadQuery.workloadWindow().queryParam("limit", "5").get("/api/v1/aggregations/allocations"));
        capture(
                "aggregation-samples-cpu",
                GoldenWorkloadQuery.workloadWindow()
                        .queryParam("flavor", "cpu")
                        .queryParam("limit", "5")
                        .get("/api/v1/aggregations/samples"));
        capture(
                "aggregation-samples-wall",
                GoldenWorkloadQuery.workloadWindow()
                        .queryParam("from", "2026-09-12T22:50:54Z")
                        .queryParam("to", "2026-09-12T22:51:00Z")
                        .queryParam("flavor", "wall")
                        .queryParam("limit", "5")
                        .get("/api/v1/aggregations/samples"));
        capture(
                "aggregation-contention",
                GoldenWorkloadQuery.workloadWindow()
                        .queryParam("minDuration", "20ms")
                        .get("/api/v1/aggregations/contention"));
        capture("aggregation-exceptions", GoldenWorkloadQuery.workloadWindow().get("/api/v1/aggregations/exceptions"));
        capture(
                "analytics-timeseries-profiler-window",
                GoldenWorkloadQuery.workloadWindow()
                        .queryParam("from", "2026-09-12T22:50:54Z")
                        .queryParam("to", "2026-09-12T22:51:00Z")
                        .queryParam("subsystem", "cpu,wall_time,allocations")
                        .queryParam("top", "5")
                        .get("/api/v1/analytics/timeseries"));
        capture(
                "analytics-timeseries-jdk-window",
                GoldenWorkloadQuery.workloadWindow()
                        .queryParam("from", "2026-09-12T22:50:54Z")
                        .queryParam("to", "2026-09-12T22:51:54Z")
                        .queryParam("subsystem", "container,heap,gc,cpu")
                        .queryParam("top", "5")
                        .get("/api/v1/analytics/timeseries"));
        capture(
                "analytics-daily-stats",
                GoldenWorkloadQuery.workloadWindow().get("/api/v1/analytics/daily_stats"));
        for (String id : java.util.List.of(
                "version-check",
                "memory",
                "gc",
                "threads",
                "performance",
                "jvm-config",
                "exceptions",
                "diagnostics",
                "proactive")) {
            capture(
                    "bundle-" + id,
                    GoldenWorkloadQuery.workloadWindow()
                            .queryParam("limit", "5")
                            .get("/api/v1/bundles/" + id));
        }
    }

    private static void capture(String name, io.restassured.response.Response response) throws Exception {
        String body = response.then().extract().asString();
        Path file = OUT.resolve(name + ".json");
        Files.writeString(file, body);
        Path meta = OUT.resolve(name + ".meta.txt");
        Files.writeString(meta, "HTTP " + response.statusCode() + "\n");
    }

}

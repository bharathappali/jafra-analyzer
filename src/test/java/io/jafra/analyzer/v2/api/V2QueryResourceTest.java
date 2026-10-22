package io.jafra.analyzer.v2.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.storage.ChunkStore;
import io.jafra.analyzer.v2.catalogue.Catalogue;
import io.jafra.analyzer.v2.rollup.RollupWriter;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

@QuarkusTest
class V2QueryResourceTest {
    @Inject
    ChunkStore chunks;

    @Inject
    RollupWriter writer;

    @Test
    void metricsMatchTheCatalogueAndFilters() {
        Catalogue catalogue = Catalogue.load();
        given().get("/v2/metrics")
                .then()
                .statusCode(200)
                .body("metrics", hasSize(121))
                .body("events", hasSize(19))
                .body("frame_kinds", hasSize(5));

        long cpu = catalogue.metrics().stream().filter(metric -> "cpu".equals(metric.group())).count();
        given().queryParam("group", "cpu").get("/v2/metrics")
                .then()
                .statusCode(200)
                .body("metrics", hasSize((int) cpu))
                .body("metrics[0].group", equalTo("cpu"));

        long jdk17 = catalogue.metrics().stream().filter(metric -> metric.onJdk(17)).count();
        given().queryParam("jdk", 17).get("/v2/metrics")
                .then()
                .statusCode(200)
                .body("metrics", hasSize((int) jdk17));
    }

    @Test
    void queryContract() throws Exception {
        String ns = "v2q";
        String pod = "pod-" + System.nanoTime();
        seed(ns, pod, "app", "cluster-a", 1_000L, 21, "{\"nmt\":\"off\"}", 0L, 10d);
        seed(ns, pod, "app", "cluster-a", 2_000L, 21, "{\"nmt\":\"off\"}", 1_000_000L, 99d);
        seedPools(pod);

        given()
                .queryParam("metric", "heap.used.bytes")
                .queryParam("from", "1000000")
                .queryParam("to", "1005000")
                .get("/v2/jvms/" + ns + "/" + pod + "/app/query")
                .then()
                .statusCode(200)
                .body("value", equalTo(99f))
                .body("unit", equalTo("bytes"))
                .body("window.from", notNullValue());

        given()
                .queryParam("metric", "heap.used.bytes")
                .queryParam("start", "1000")
                .queryParam("from", "0")
                .queryParam("to", "5000")
                .get("/v2/jvms/" + ns + "/" + pod + "/app/query")
                .then()
                .statusCode(200)
                .body("value", equalTo(10f));

        given()
                .queryParam("metric", "heap.used.bytes")
                .get("/v2/jvms/" + ns + "/" + pod + "/missing/query")
                .then()
                .statusCode(404)
                .body("error", equalTo("unknown_jvm"));

        given()
                .queryParam("metric", "heap.used.byt")
                .queryParam("from", "0")
                .queryParam("to", "5000")
                .get("/v2/jvms/" + ns + "/" + pod + "/app/query")
                .then()
                .statusCode(400)
                .body("error", equalTo("unknown_metric"))
                .body("valid", hasSize(3));

        given()
                .queryParam("metric", "heap.used.bytes")
                .queryParam("stat", "p99")
                .queryParam("from", "1000000")
                .queryParam("to", "1005000")
                .get("/v2/jvms/" + ns + "/" + pod + "/app/query")
                .then()
                .statusCode(400)
                .body("error", equalTo("unsupported_stat"))
                .body("valid", org.hamcrest.Matchers.hasItems("avg", "min", "max"));

        given()
                .queryParam("metric", "heap.used.bytes")
                .queryParam("by", "class")
                .queryParam("from", "1000000")
                .queryParam("to", "1005000")
                .get("/v2/jvms/" + ns + "/" + pod + "/app/query")
                .then()
                .statusCode(400)
                .body("error", equalTo("unsupported_by"));

        given()
                .queryParam("metric", "endpoint.slow.pct")
                .queryParam("from", "1000000")
                .queryParam("to", "1005000")
                .get("/v2/jvms/" + ns + "/" + pod + "/app/query")
                .then()
                .statusCode(422)
                .body("error", equalTo("not_on_this_jdk"))
                .body("message", equalTo("needs JDK 25"));

        given()
                .queryParam("metric", "nmt.committed.bytes")
                .queryParam("from", "1000000")
                .queryParam("to", "1005000")
                .get("/v2/jvms/" + ns + "/" + pod + "/app/query")
                .then()
                .statusCode(422)
                .body("error", equalTo("nmt_off"))
                .body("hint", equalTo("set pod annotation jafra.io/jdk-nmt: \"enabled\""));

        given()
                .queryParam("metric", "heap.used.bytes")
                .queryParam("from", "9000000")
                .queryParam("to", "9005000")
                .get("/v2/jvms/" + ns + "/" + pod + "/app/query")
                .then()
                .statusCode(404)
                .body("error", equalTo("no_data"))
                .body("valid", hasSize(1));

        given()
                .queryParam("metric", "heap.used.bytes")
                .queryParam("last", "15m")
                .queryParam("from", "0")
                .get("/v2/jvms/" + ns + "/" + pod + "/app/query")
                .then()
                .statusCode(400)
                .body("error", equalTo("bad_range"));

        Map<String, Object> stepped = given()
                .queryParam("metric", "heap.used.bytes")
                .queryParam("step", "5s")
                .queryParam("from", "0")
                .queryParam("to", "400000")
                .get("/v2/jvms/v2step/" + pod + "/app/query")
                .then()
                .statusCode(200)
                .body("step_used", equalTo("10s"))
                .body("points.size()", lessThanOrEqualTo(60))
                .body("summary.min", notNullValue())
                .extract()
                .jsonPath()
                .getMap("");
        assertTrue(stepped.containsKey("step_used"));
        assertTrue(!stepped.containsKey("stepUsed"));

        given()
                .queryParam("metric", "heap.pool.used.bytes")
                .queryParam("by", "pool")
                .queryParam("from", "0")
                .queryParam("to", "5000")
                .get("/v2/jvms/v2by/" + pod + "/app/query")
                .then()
                .statusCode(200)
                .body("rows", hasSize(10))
                .body("other_pct", greaterThan(0f));

        given()
                .queryParam("metric", "heap.pool.used.bytes")
                .queryParam("by", "pool")
                .queryParam("step", "5s")
                .queryParam("from", "0")
                .queryParam("to", "5000")
                .get("/v2/jvms/v2by/" + pod + "/app/query")
                .then()
                .statusCode(200)
                .body("series", hasSize(3));

        given()
                .queryParam("metric", "heap.pool.used.bytes")
                .queryParam("where", "pool=p0")
                .queryParam("from", "0")
                .queryParam("to", "5000")
                .get("/v2/jvms/v2by/" + pod + "/app/query")
                .then()
                .statusCode(200)
                .body("value", equalTo(1f));

        seed(ns, pod, "app", "cluster-b", 3_000L, 21, "{\"nmt\":\"off\"}", 2_000_000L, 1d);
        given()
                .queryParam("metric", "heap.used.bytes")
                .get("/v2/jvms/" + ns + "/" + pod + "/app/query")
                .then()
                .statusCode(409)
                .body("error", equalTo("ambiguous_cluster"))
                .body("valid", hasSize(2));
    }

    @Test
    void notReadyIs503() {
        chunks.setReady(false);
        try {
            given().get("/v2/metrics")
                    .then()
                    .statusCode(503)
                    .header("Retry-After", "30")
                    .body("error", equalTo("not_ready"));
            given().get("/api/v1/recordings")
                    .then()
                    .statusCode(not(equalTo(404)));
        } finally {
            chunks.setReady(true);
        }
    }

    private void seedPools(String pod) throws Exception {
        WindowData data = window("v2by", pod, "app", "cluster-a", 5_000L, 21, "{}", 0L);
        for (int i = 0; i < 12; i++) {
            data.add("heap.pool.used.bytes", "pool", "pool=p" + i, i + 1d);
        }
        writer.commitSynthetic("pools-" + pod, data);
        for (int i = 0; i < 80; i++) {
            WindowData point = window("v2step", pod, "app", "cluster-a", 6_000L, 21, "{}", i * 5_000L);
            point.add("heap.used.bytes", "", "", i);
            writer.commitSynthetic("step-" + pod + "-" + i, point);
        }
    }

    private void seed(
            String ns, String pod, String container, String cluster, long start, int jdk, String settings, long from, double value)
            throws Exception {
        WindowData data = window(ns, pod, container, cluster, start, jdk, settings, from);
        data.add("heap.used.bytes", "", "", value);
        writer.commitSynthetic(cluster + "-" + ns + "-" + pod + "-" + start + "-" + from, data);
    }

    private static WindowData window(
            String ns, String pod, String container, String cluster, long start, int jdk, String settings, long from) {
        WindowData data = new WindowData();
        data.clusterId = cluster;
        data.namespace = ns;
        data.podName = pod;
        data.podUid = "uid";
        data.containerName = container;
        data.jvmStartMs = start;
        data.jdkMajor = jdk;
        data.jdkVersion = jdk + ".0.0";
        data.profile = "aggressive";
        data.settingsJson = settings;
        data.fromMs = from;
        data.toMs = from + 5_000;
        data.dtSeconds = 5;
        return data;
    }
}

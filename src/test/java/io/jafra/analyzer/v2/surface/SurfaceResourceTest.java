package io.jafra.analyzer.v2.surface;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.rollup.RollupWriter;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class SurfaceResourceTest {
    @Inject
    RollupWriter writer;

    @Test
    void reportChartsTimelineAndWorkloads() throws Exception {
        String pod = "pod-" + System.nanoTime();
        WindowData first = window(pod, 0L, 40d);
        WindowData second = window(pod, 5_000L, 80d);
        writer.commitSynthetic("surface-a-" + pod, first);
        writer.commitSynthetic("surface-b-" + pod, second);

        given()
                .queryParam("from", "0")
                .queryParam("to", "10000")
                .get("/v2/jvms/v2surf/" + pod + "/app/report")
                .then()
                .statusCode(200)
                .body("groups", hasSize(18))
                .body("groups[0].group", equalTo("heap"))
                .body("groups[0].state", equalTo("ok"))
                .body("groups[0].flags", hasItem("unproven_baseline"))
                .body("groups.find { it.group == 'endpoint' }.state", equalTo("unavailable"))
                .body("groups.find { it.group == 'endpoint' }.flags", hasItem("not_on_this_jdk"))
                .body("groups.find { it.group == 'nmt' }.state", equalTo("unavailable"))
                .body("groups.find { it.group == 'nmt' }.flags", hasItem("nmt_off"));

        given()
                .get("/v2/jvms/v2surf/" + pod + "/app/charts")
                .then()
                .statusCode(200)
                .body("charts", hasSize(37))
                .body("charts.find { it.id == 'cpu-limit' }.availability", equalTo("available"))
                .body("charts.find { it.id == 'endpoints-time' }.availability", equalTo("not_on_this_jdk"))
                .body("charts.find { it.id == 'nmt' }.availability", equalTo("nmt_off"));

        given()
                .queryParam("view", "not-a-view")
                .queryParam("from", "0")
                .queryParam("to", "10000")
                .queryParam("since", "0")
                .get("/v2/jvms/v2surf/" + pod + "/app/charts/heap-used")
                .then()
                .statusCode(200)
                .body("view", equalTo("not-a-view"))
                .body("metrics", hasSize(4))
                .body("metrics.find { it.name == 'heap.used.pct_max' }.points", hasSize(1))
                .body("metrics.find { it.name == 'heap.used.pct_max' }.points[0].t", equalTo(5000));

        given()
                .queryParam("from", "0")
                .queryParam("to", "10000")
                .get("/v2/jvms/v2surf/" + pod + "/app/charts/missing")
                .then()
                .statusCode(404)
                .body("error", equalTo("unknown_chart"))
                .body("valid", hasSize(37));

        given()
                .queryParam("from", "0")
                .queryParam("to", "10000")
                .get("/v2/jvms/v2surf/" + pod + "/app/timeline")
                .then()
                .statusCode(200)
                .body("points.size()", org.hamcrest.Matchers.lessThanOrEqualTo(360))
                .body("points[0].t", org.hamcrest.Matchers.instanceOf(Number.class));

        given()
                .queryParam("cluster", "cluster-a")
                .get("/v2/workloads")
                .then()
                .statusCode(200)
                .body("workloads.findAll { it.pod == '" + pod + "' }", hasSize(1))
                .body("workloads.find { it.pod == '" + pod + "' }.namespace", equalTo("v2surf"));
    }

    private static WindowData window(String pod, long from, double heapPct) {
        WindowData data = new WindowData();
        data.clusterId = "cluster-a";
        data.namespace = "v2surf";
        data.podName = pod;
        data.podUid = "uid";
        data.containerName = "app";
        data.jvmStartMs = 4_000L;
        data.jdkMajor = 21;
        data.jdkVersion = "21.0.0";
        data.profile = "aggressive";
        data.settingsJson = "{\"nmt\":\"off\"}";
        data.fromMs = from;
        data.toMs = from + 5_000;
        data.dtSeconds = 5;
        data.add("heap.used.pct_max", "", "", heapPct);
        return data;
    }
}

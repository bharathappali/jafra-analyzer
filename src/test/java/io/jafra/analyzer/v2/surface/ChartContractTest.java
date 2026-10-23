package io.jafra.analyzer.v2.surface;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.rollup.RollupWriter;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class ChartContractTest {
    @Inject
    RollupWriter writer;

    @Test
    void chartSeriesPointsAreArraysAndHeapCarriesATrendLine() throws Exception {
        String pod = "pod-" + System.nanoTime();
        for (int i = 0; i < 31; i++) {
            writer.commitSynthetic("chart-" + i + "-" + pod, window(pod, i * 10_000L, 10d + i));
        }

        String base = "/v2/jvms/v2chart/" + pod + "/app";
        given()
                .queryParam("from", "0")
                .queryParam("to", "310000")
                .queryParam("step", "5s")
                .get(base + "/charts/heap-used")
                .then()
                .statusCode(200)
                .body("id", equalTo("heap-used"))
                .body("window.step", equalTo("5s"))
                .body("step_used", equalTo("5s"))
                .body("series.find { it.metric == 'heap.used.pct_max' }.points", hasSize(31))
                .body("series.find { it.metric == 'heap.used.pct_max' }.points[0]", hasSize(2))
                .body("series.find { it.metric == 'heap.used.pct_max' }.points[0][0]", equalTo(0))
                .body("series.find { it.metric == 'heap.used.pct_max' }.points[30][1]", equalTo(40f))
                .body("summary.'heap.used.pct_max'.min", equalTo(10f))
                .body("summary.'heap.used.pct_max'.min_at", equalTo(0))
                .body("summary.'heap.used.pct_max'.max_at", equalTo(300000))
                .body("trend.metric", equalTo("heap.used.bytes"))
                .body("trend.line", hasSize(2))
                .body("trend.line[0]", hasSize(2))
                .body("flame", nullValue());

        given()
                .queryParam("from", "0")
                .queryParam("to", "310000")
                .queryParam("since", "290000")
                .get(base + "/charts/heap-used")
                .then()
                .statusCode(200)
                .body("series.find { it.metric == 'heap.used.pct_max' }.points", hasSize(1))
                .body("series.find { it.metric == 'heap.used.pct_max' }.points[0][0]", equalTo(300000));

        given()
                .get(base + "/charts")
                .then()
                .statusCode(200)
                .body("charts.find { it.id == 'heap-used' }.note", not(emptyOrNullString()));
    }

    private static WindowData window(String pod, long from, double heapPct) {
        WindowData data = new WindowData();
        data.clusterId = "cluster-a";
        data.namespace = "v2chart";
        data.podName = pod;
        data.podUid = "uid";
        data.containerName = "app";
        data.jvmStartMs = 4_000L;
        data.jdkMajor = 21;
        data.jdkVersion = "21.0.0";
        data.profile = "aggressive";
        data.settingsJson = "{}";
        data.fromMs = from;
        data.toMs = from + 5_000L;
        data.dtSeconds = 5;
        data.add("heap.used.pct_max", "", "", heapPct);
        data.add("heap.used.bytes", "", "", heapPct * 1_000_000d);
        data.add("heap.committed.bytes", "", "", 2_000_000d);
        data.add("heap.max.bytes", "", "", 4_000_000d);
        return data;
    }
}

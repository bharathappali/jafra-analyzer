package io.jafra.analyzer.v2.trend;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.rollup.RollupWriter;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class TrendResourceTest {
    @Inject
    RollupWriter writer;

    @Test
    void rejectsNonTrendMetricsAndLeavesAnUnsetMetaspaceLimitNull() throws Exception {
        String pod = "pod-" + System.nanoTime();
        for (int i = 0; i < 35; i++) {
            WindowData data = new WindowData();
            data.clusterId = "cluster-a";
            data.namespace = "v2trend";
            data.podName = pod;
            data.podUid = "uid";
            data.containerName = "app";
            data.jvmStartMs = 9_000L;
            data.jdkMajor = 21;
            data.jdkVersion = "21.0.0";
            data.profile = "aggressive";
            data.settingsJson = "{\"flags\":{\"MaxMetaspaceSize\":\"not set\",\"source\":\"jvmArguments\"}}";
            data.fromMs = i * 60_000L;
            data.toMs = data.fromMs + 5_000;
            data.dtSeconds = 5;
            data.add("metaspace.used.bytes", "", "", 1_000_000d + i);
            writer.commitSynthetic("trend-" + pod + "-" + i, data);
        }
        String base = "/v2/jvms/v2trend/" + pod + "/app/trend";
        given()
                .queryParam("metric", "heap.used.pct_max")
                .queryParam("from", "0")
                .queryParam("to", "2100000")
                .get(base)
                .then()
                .statusCode(400)
                .body("valid", hasSize(9));

        given()
                .queryParam("metric", "metaspace.used.bytes")
                .queryParam("from", "0")
                .queryParam("to", "2100000")
                .get(base)
                .then()
                .statusCode(200)
                .body("limit", nullValue())
                .body("limit_source", nullValue())
                .body("shape", equalTo("rising"));
    }
}

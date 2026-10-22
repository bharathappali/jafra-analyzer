package io.jafra.analyzer.v2.surface;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.rollup.RollupWriter;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;

@QuarkusTest
class OverviewShapeTest {
    @Inject
    RollupWriter writer;

    @Test
    void overviewIsWindowJdkAndEighteenLanes() throws Exception {
        String pod = "pod-" + System.nanoTime();
        writer.commitSynthetic("ov-" + pod, window(pod));
        JsonPath body = given()
                .queryParam("from", "0")
                .queryParam("to", "10000")
                .get("/v2/jvms/v2ov/" + pod + "/app/report")
                .then()
                .statusCode(200)
                .body("groups", hasSize(18))
                .body("groups.find { it.group == 'heap' }.stat", equalTo("avg"))
                .body("groups.find { it.group == 'heap' }.open_incidents", hasSize(0))
                .body("groups.find { it.group == 'endpoint' }.normal", nullValue())
                .body("groups.find { it.group == 'endpoint' }.vs_normal", nullValue())
                .body("jdk", notNullValue())
                .extract()
                .jsonPath();
        assertEquals(Set.of("window", "jdk", "groups"), body.getMap("").keySet());
    }

    private static WindowData window(String pod) {
        WindowData data = new WindowData();
        data.clusterId = "cluster-a";
        data.namespace = "v2ov";
        data.podName = pod;
        data.podUid = "uid";
        data.containerName = "app";
        data.jvmStartMs = 4_000L;
        data.jdkMajor = 21;
        data.jdkVersion = "21.0.0";
        data.profile = "aggressive";
        data.settingsJson = "{\"nmt\":\"off\"}";
        data.fromMs = 0L;
        data.toMs = 5_000L;
        data.dtSeconds = 5;
        data.add("heap.used.pct_max", "", "", 40d);
        return data;
    }
}

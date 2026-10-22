package io.jafra.analyzer.v2.tools;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.rollup.RollupWriter;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;

@QuarkusTest
class ToolResourceTest {
    @Inject
    RollupWriter writer;

    @Test
    void schemasAndToolsMatchTheHttpEndpoints() throws Exception {
        String pod = "pod-" + System.nanoTime();
        WindowData data = new WindowData();
        data.clusterId = "cluster-a";
        data.namespace = "v2tool";
        data.podName = pod;
        data.podUid = "uid";
        data.containerName = "app";
        data.jvmStartMs = 4_000L;
        data.jdkMajor = 21;
        data.jdkVersion = "21.0.0";
        data.profile = "aggressive";
        data.settingsJson = "{\"nmt\":\"off\",\"flags\":{}}";
        data.fromMs = 0L;
        data.toMs = 5_000L;
        data.dtSeconds = 5;
        data.add("heap.used.pct_max", "", "", 40d);
        writer.commitSynthetic("tool-" + pod, data);

        given()
                .get("/v2/tools")
                .then()
                .statusCode(200)
                .body("tools", hasSize(8))
                .body("tools.name", hasItem("get_overview"))
                .body("tools.name", hasItem("list_incidents"))
                .body("tools.name", hasItem("get_incident"))
                .body("tools.name", hasItem("query_metric"))
                .body("tools.name", hasItem("get_trend"))
                .body("tools.name", hasItem("list_events"))
                .body("tools.name", hasItem("top_frames"))
                .body("tools.name", hasItem("get_jvm_info"))
                .body("tools.find { it.name == 'get_incident' }.parameters.required", hasItem("id"))
                .body("tools.find { it.name == 'top_frames' }.parameters.properties.kind.enum", hasItem("wall"))
                .body("context.properties.range.type", equalTo("object"));

        String base = "/v2/jvms/v2tool/" + pod + "/app";
        assertEquals(body(base + "/report", "from", "0", "to", "10000"), body(base + "/tools/get_overview", "from", "0", "to", "10000"));
        assertEquals(
                body(base + "/query", "metric", "heap.used.pct_max", "from", "0", "to", "10000"),
                body(base + "/tools/query_metric", "metric", "heap.used.pct_max", "from", "0", "to", "10000"));
        assertEquals(body(base + "/incidents", "from", "0", "to", "10000"), body(base + "/tools/list_incidents", "from", "0", "to", "10000"));
        assertEquals(
                error(base + "/query", "metric", "no.such.metric"),
                error(base + "/tools/query_metric", "metric", "no.such.metric"));
        assertEquals(error(base + "/incidents/missing"), error(base + "/tools/get_incident", "id", "missing"));

        given()
                .get(base + "/tools/get_jvm_info")
                .then()
                .statusCode(200)
                .body("k8s.namespace", equalTo("v2tool"))
                .body("k8s.pod", equalTo(pod))
                .body("k8s.container", equalTo("app"))
                .body("flags.source", equalTo("jvmArguments"));
    }

    private static String body(String path, String... params) {
        return call(path, 200, params).asString();
    }

    private static String error(String path, String... params) {
        Response response = call(path, -1, params);
        return response.statusCode() + ":" + response.asString();
    }

    private static Response call(String path, int expected, String... params) {
        var spec = given();
        for (int i = 0; i < params.length; i += 2) {
            spec.queryParam(params[i], params[i + 1]);
        }
        Response response = spec.get(path);
        if (expected > 0) {
            response.then().statusCode(expected);
        }
        return response;
    }
}

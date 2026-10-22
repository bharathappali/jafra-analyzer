package io.jafra.analyzer.v2.tools;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.rollup.RollupWriter;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;

@QuarkusTest
class JvmInfoShapeTest {
    @Inject
    RollupWriter writer;

    @Test
    void jvmInfoHasTheTenContractKeys() throws Exception {
        String pod = "pod-" + System.nanoTime();
        WindowData data = new WindowData();
        data.clusterId = "cluster-a";
        data.namespace = "v2info";
        data.podName = pod;
        data.podUid = "uid";
        data.containerName = "app";
        data.jvmStartMs = 4_000L;
        data.jdkMajor = 21;
        data.jdkVersion = "21.0.0";
        data.profile = "aggressive";
        data.settingsJson = "{\"nmt\":\"off\",\"jvm\":{\"version\":\"21.0.0\"},\"flags\":{}}";
        data.fromMs = 0L;
        data.toMs = 5_000L;
        data.dtSeconds = 5;
        data.add("heap.used.pct_max", "", "", 10d);
        writer.commitSynthetic("info-" + pod, data);

        JsonPath body = given()
                .get("/v2/jvms/v2info/" + pod + "/app/tools/get_jvm_info")
                .then()
                .statusCode(200)
                .body("k8s.namespace", equalTo("v2info"))
                .body("k8s.pod", equalTo(pod))
                .body("k8s.container", equalTo("app"))
                .body("flags.source", equalTo("jvmArguments"))
                .body("jvm.version", equalTo("21.0.0"))
                .extract()
                .jsonPath();
        assertEquals(
                Set.of("jvm", "heap", "gc", "jit", "code_cache", "container", "host", "flags", "recording", "k8s"),
                body.getMap("").keySet());
    }
}

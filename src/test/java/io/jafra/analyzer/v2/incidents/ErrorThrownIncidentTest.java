package io.jafra.analyzer.v2.incidents;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.rollup.RollupWriter;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class ErrorThrownIncidentTest {
    @Inject
    RollupWriter writer;

    @Test
    void errorThrownStaysWarningAfterAnOkWindow() throws Exception {
        String pod = "pod-" + System.nanoTime();
        WindowData error = window(pod, 0L);
        error.event(new WindowData.StoredEvent(
                "error",
                1_000L,
                null,
                "main",
                "com.example.App.main",
                null,
                "{\"thrownClass\":\"java.lang.NoClassDefFoundError\",\"message\":\"missing\"}"));
        writer.commitSynthetic("err-" + pod, error);
        writer.commitSynthetic("ok-" + pod, window(pod, 5_000L));

        String base = "/v2/jvms/v2err/" + pod + "/app";
        String id = given()
                .queryParam("from", "0")
                .queryParam("to", "20000")
                .get(base + "/incidents")
                .then()
                .statusCode(200)
                .body("incidents.find { it.type == 'error_thrown' }.level", equalTo("warning"))
                .extract()
                .path("incidents.find { it.type == 'error_thrown' }.id");

        given()
                .get(base + "/incidents/" + id)
                .then()
                .statusCode(200)
                .body("level", equalTo("warning"))
                .body("headline", not(emptyOrNullString()))
                .body("who.kind", equalTo("class"))
                .body("who.name", not(emptyOrNullString()))
                .body("score", greaterThan(0f));
    }

    private static WindowData window(String pod, long from) {
        WindowData data = new WindowData();
        data.clusterId = "cluster-a";
        data.namespace = "v2err";
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
        data.add("heap.used.pct_max", "", "", 40d);
        return data;
    }
}

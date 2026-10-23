package io.jafra.analyzer.v2.questions;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.rollup.RollupWriter;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;

@QuarkusTest
class QuestionsHttpTest {
    @Inject
    RollupWriter writer;

    @Test
    void incidentsChartsAndStartersCarryQuestions() throws Exception {
        String pod = "pod-" + System.nanoTime();
        writer.commitSynthetic("q-" + pod, window(pod));

        String base = "/v2/jvms/v2q/" + pod + "/app";
        JsonPath listed = given()
                .queryParam("from", "0")
                .queryParam("to", "20000")
                .get(base + "/incidents")
                .then()
                .statusCode(200)
                .body("incidents.find { it.type == 'error_thrown' }.questions.developer.size()", greaterThanOrEqualTo(3))
                .body("incidents.find { it.type == 'error_thrown' }.questions.expert.size()", greaterThanOrEqualTo(3))
                .body("incidents.find { it.type == 'error_thrown' }.questions.developer", everyItem(not(containsString("{"))))
                .body("incidents.find { it.type == 'error_thrown' }.questions.expert", everyItem(not(containsString("{"))))
                .extract()
                .jsonPath();
        String id = listed.getString("incidents.find { it.type == 'error_thrown' }.id");
        assertStrings(listed.getList("incidents.find { it.type == 'error_thrown' }.questions.developer"));
        assertStrings(listed.getList("incidents.find { it.type == 'error_thrown' }.questions.expert"));

        JsonPath detail = given()
                .get(base + "/incidents/" + id)
                .then()
                .statusCode(200)
                .body("questions.developer.size()", greaterThanOrEqualTo(3))
                .body("questions.expert.size()", greaterThanOrEqualTo(3))
                .body("questions.developer", everyItem(not(containsString("{"))))
                .body("questions.expert", everyItem(not(containsString("{"))))
                .extract()
                .jsonPath();
        assertStrings(detail.getList("questions.developer"));
        assertStrings(detail.getList("questions.expert"));

        JsonPath chart = given()
                .queryParam("from", "0")
                .queryParam("to", "20000")
                .get(base + "/charts/heap-used")
                .then()
                .statusCode(200)
                .body("questions.developer.size()", greaterThanOrEqualTo(3))
                .body("questions.expert.size()", greaterThanOrEqualTo(3))
                .body("questions.developer", everyItem(not(containsString("{"))))
                .body("questions.expert", everyItem(not(containsString("{"))))
                .body("range_questions.developer.size()", greaterThanOrEqualTo(3))
                .body("range_questions.expert.size()", greaterThanOrEqualTo(3))
                .body("range_questions.developer.findAll { it.contains('{from}') }.size()", greaterThanOrEqualTo(1))
                .body("range_questions.developer.findAll { it.contains('{to}') }.size()", greaterThanOrEqualTo(1))
                .body("range_questions.expert.findAll { it.contains('{from}') }.size()", greaterThanOrEqualTo(1))
                .body("range_questions.expert.findAll { it.contains('{to}') }.size()", greaterThanOrEqualTo(1))
                .extract()
                .jsonPath();
        assertStrings(chart.getList("questions.developer"));
        assertStrings(chart.getList("range_questions.expert"));

        JsonPath starters = given()
                .get(base + "/questions/starters")
                .then()
                .statusCode(200)
                .body("developer.size()", greaterThanOrEqualTo(3))
                .body("expert.size()", greaterThanOrEqualTo(3))
                .extract()
                .jsonPath();
        assertStrings(starters.getList("developer"));
        assertStrings(starters.getList("expert"));
    }

    private static void assertStrings(List<?> lines) {
        assertTrue(lines.size() >= 3);
        for (Object line : lines) {
            assertInstanceOf(String.class, line);
        }
    }

    private static WindowData window(String pod) {
        WindowData data = new WindowData();
        data.clusterId = "cluster-a";
        data.namespace = "v2q";
        data.podName = pod;
        data.podUid = "uid";
        data.containerName = "app";
        data.jvmStartMs = 4_000L;
        data.jdkMajor = 21;
        data.jdkVersion = "21.0.0";
        data.profile = "aggressive";
        data.settingsJson = "{}";
        data.fromMs = 0L;
        data.toMs = 5_000L;
        data.dtSeconds = 5;
        data.add("heap.used.pct_max", "", "", 40d);
        data.event(new WindowData.StoredEvent(
                "error",
                1_000L,
                null,
                "main",
                "com.example.App.main",
                null,
                "{\"thrownClass\":\"java.lang.NoClassDefFoundError\",\"message\":\"missing\"}"));
        return data;
    }
}

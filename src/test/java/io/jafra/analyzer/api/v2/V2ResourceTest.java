package io.jafra.analyzer.api.v2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class V2ResourceTest {
    @Test
    void missingInstanceIs400AndBeforeStaysOnV1() {
        given().queryParam("ns", "default").queryParam("pod", "pod").queryParam("container", "app").queryParam("metrics", "cpu.jvm_user")
                .get("/v2/series")
                .then()
                .statusCode(400);
        given().queryParam("ns", "default")
                .queryParam("pod", "pod")
                .queryParam("container", "app")
                .queryParam("instance", "latest")
                .queryParam("before", "2026-09-12T00:00:00Z")
                .queryParam("metrics", "cpu.jvm_user")
                .get("/v2/series")
                .then()
                .statusCode(400)
                .body("error", equalTo("before and after are v1 windows"));
    }

    @Test
    void workloadsDoesNotRequireAnInstance() {
        given().get("/v2/workloads").then().statusCode(200);
    }

    @Test
    void presetsDoesNotRequireAnInstance() {
        given().get("/v2/presets").then().statusCode(200).body("presets.size()", equalTo(3));
    }

    @Test
    void retentionReportsTheModeAndReadsSendAnEtag() {
        given().get("/v2/retention").then().statusCode(200).body("mode", equalTo("never")).body("raw_ttl_h", equalTo(48));
        String etag = given().get("/v2/workloads").then().statusCode(200).extract().header("ETag");
        given().header("If-None-Match", etag).get("/v2/workloads").then().statusCode(304);
    }
}

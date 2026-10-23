package io.jafra.analyzer.v2.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class CorsFilterTest {
    @Test
    void preflightGetAndErrorCarryCorsHeaders() {
        given()
                .header("Origin", "http://127.0.0.1:5173")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Content-Type")
                .options("/v2/workloads")
                .then()
                .statusCode(204)
                .header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Expose-Headers", "Retry-After")
                .header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                .header("Access-Control-Allow-Headers", "Content-Type");

        given()
                .get("/v2/workloads")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Expose-Headers", "Retry-After");

        given()
                .get("/v2/jvms/missing-ns/missing-pod/missing-container/report")
                .then()
                .statusCode(404)
                .body("error", equalTo("unknown_jvm"))
                .header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Expose-Headers", "Retry-After");
    }
}

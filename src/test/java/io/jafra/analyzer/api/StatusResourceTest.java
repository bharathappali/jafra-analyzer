package io.jafra.analyzer.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class StatusResourceTest {
    @Test
    void statusAndHealthAreReady() {
        given().when().get("/api/v1/status")
                .then()
                .statusCode(200)
                .body("service", equalTo("jafra-analyzer"))
                .body("status", equalTo("ready"));
        given().when().get("/q/health").then().statusCode(200);
        given().when().get("/health").then().statusCode(200);
    }
}

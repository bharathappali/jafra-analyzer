package io.jafra.analyzer.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class FlatApiResourceTest {
    @Test
    void nestedWorkloadSummaryAndReportRedirectToFlat() {
        given().when()
                .get("/api/v1/namespaces/ns/pods/pod/containers/c/summary")
                .then()
                .statusCode(399)
                .header(
                        "Location",
                        equalTo("/api/v1/summary?namespace=ns&pod=pod&container=c"));

        given().when()
                .queryParam("filter", "heap")
                .get("/api/v1/namespaces/ns/pods/pod/containers/c/report")
                .then()
                .statusCode(399)
                .header(
                        "Location",
                        equalTo("/api/v1/report?namespace=ns&pod=pod&container=c&filter=heap"));

        given().when()
                .get("/api/v1/namespaces/ns/pods/pod/containers/c")
                .then()
                .statusCode(399)
                .header(
                        "Location",
                        equalTo("/api/v1/workload?namespace=ns&pod=pod&container=c"));

        given().when()
                .get("/api/v1/namespaces/ns/pods/pod/containers/c/recordings/profile-1.jfr/summary")
                .then()
                .statusCode(399)
                .header(
                        "Location",
                        equalTo("/api/v1/summary?namespace=ns&pod=pod&container=c&recording=profile-1.jfr"));
    }

    @Test
    void flatSummaryRequiresWorkloadParams() {
        given().when()
                .get("/api/v1/summary")
                .then()
                .statusCode(400)
                .body("status", equalTo("bad_request"));
    }
}

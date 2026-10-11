package io.jafra.analyzer.test;

import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

/** Query params for golden JFR fixture workload ({@code auth-cache} in {@code default}). */
public final class GoldenWorkloadQuery {
    public static final String NAMESPACE = "default";
    public static final String POD = "auth-cache-84cd9fbc49-fttcq";
    public static final String CONTAINER = "auth-cache";
    /** Fixture chunk times span roughly 2026-09-12 through 2026-09-13 UTC. */
    public static final String FROM = "2026-09-12T00:00:00Z";
    public static final String TO = "2026-09-14T00:00:00Z";

    private GoldenWorkloadQuery() {}

    public static RequestSpecification workloadWindow() {
        return given()
                .queryParam("namespace", NAMESPACE)
                .queryParam("pod", POD)
                .queryParam("container", CONTAINER)
                .queryParam("from", FROM)
                .queryParam("to", TO);
    }
}

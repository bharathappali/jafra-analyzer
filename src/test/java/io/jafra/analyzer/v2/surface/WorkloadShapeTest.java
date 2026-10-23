package io.jafra.analyzer.v2.surface;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.api.V2Times;
import io.jafra.analyzer.v2.rollup.RollupWriter;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class WorkloadShapeTest {
    @Inject
    RollupWriter writer;

    @Test
    void workloadsAreContainersWithJvmsNewestFirst() throws Exception {
        String ns = "ns-" + System.nanoTime();
        long older = 1_000_000L;
        long newer = 2_000_000L;
        writer.commitSynthetic("w-a-" + ns, window(ns, "pod-b", "app", older, 0L));
        writer.commitSynthetic("w-b-" + ns, window(ns, "pod-a", "app", newer, 0L));
        writer.commitSynthetic("w-c-" + ns, window(ns, "pod-a", "app", older, 5_000L));

        given()
                .queryParam("cluster", "cluster-a")
                .get("/v2/workloads")
                .then()
                .statusCode(200)
                .body("findAll { it.ns == '" + ns + "' }.size()", equalTo(2))
                .body("findAll { it.ns == '" + ns + "' }[0].pod", equalTo("pod-a"))
                .body("findAll { it.ns == '" + ns + "' }[0].ns", equalTo(ns))
                .body("findAll { it.ns == '" + ns + "' }[0].container", equalTo("app"))
                .body("findAll { it.ns == '" + ns + "' }[0].jvms.size()", equalTo(2))
                .body("findAll { it.ns == '" + ns + "' }[0].jvms[0].start", equalTo(V2Times.text(newer)))
                .body("findAll { it.ns == '" + ns + "' }[0].jvms[0].jdk", equalTo(21))
                .body("findAll { it.ns == '" + ns + "' }[0].jvms[0].profile", equalTo("low"))
                .body("findAll { it.ns == '" + ns + "' }[0].jvms[0].first", equalTo(V2Times.text(0L)))
                .body("findAll { it.ns == '" + ns + "' }[0].jvms[1].start", equalTo(V2Times.text(older)))
                .body("findAll { it.ns == '" + ns + "' }[1].pod", equalTo("pod-b"));
    }

    private static WindowData window(String ns, String pod, String container, long start, long from) {
        WindowData data = new WindowData();
        data.clusterId = "cluster-a";
        data.namespace = ns;
        data.podName = pod;
        data.podUid = "uid";
        data.containerName = container;
        data.jvmStartMs = start;
        data.jdkMajor = 21;
        data.jdkVersion = "21.0.0";
        data.profile = "low";
        data.settingsJson = "{}";
        data.fromMs = from;
        data.toMs = from + 5_000L;
        data.dtSeconds = 5;
        data.add("heap.used.pct_max", "", "", 10d);
        return data;
    }
}

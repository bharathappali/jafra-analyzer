package io.jafra.analyzer.v2.surface;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.api.JvmResolver;
import io.jafra.analyzer.v2.rollup.RollupWriter;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.jafra.analyzer.v2.store.V2Store;
import io.jafra.analyzer.v2.store.V2Store.IncidentRecord;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class TimelineTest {
    @Inject
    RollupWriter writer;

    @Inject
    V2Store store;

    @Inject
    JvmResolver jvms;

    @Test
    void segmentsFollowAnIncidentSpan() throws Exception {
        String pod = "pod-" + System.nanoTime();
        for (int i = 0; i < 3; i++) {
            writer.commitSynthetic("tl-" + i + "-" + pod, window(pod, i * 5_000L));
        }
        JvmRow jvm = jvms.resolve("v2tl", pod, "app", null, null);
        String id = "span-" + pod;
        store.upsertIncident(new IncidentRecord(
                id, jvm.jvmId(), "heap_near_full", "heap", "critical", "open", 5_000L, 9_999L, 5_000L, 90d, "strong",
                "heap", "felt", "{}", "{}", "{\"related\":[],\"groups\":[]}", "null", "[]", "[]", "{}", "[]"));

        given()
                .queryParam("from", "0")
                .queryParam("to", "15000")
                .get("/v2/jvms/v2tl/" + pod + "/app/timeline")
                .then()
                .statusCode(200)
                .body("lanes", hasSize(18))
                .body("lanes.find { it.group == 'heap' }.segments", hasSize(3))
                .body("lanes.find { it.group == 'heap' }.segments[0].state", equalTo("ok"))
                .body("lanes.find { it.group == 'heap' }.segments[1].state", equalTo("critical"))
                .body("lanes.find { it.group == 'heap' }.segments[2].state", equalTo("ok"))
                .body("lanes.find { it.group == 'cpu' }.segments", hasSize(1))
                .body("incidents", hasSize(1))
                .body("incidents[0].id", equalTo(id));
    }

    private static WindowData window(String pod, long from) {
        WindowData data = new WindowData();
        data.clusterId = "cluster-a";
        data.namespace = "v2tl";
        data.podName = pod;
        data.podUid = "uid";
        data.containerName = "app";
        data.jvmStartMs = 4_000L;
        data.jdkMajor = 21;
        data.jdkVersion = "21.0.0";
        data.profile = "aggressive";
        data.settingsJson = "{\"nmt\":\"off\"}";
        data.fromMs = from;
        data.toMs = from + 5_000L;
        data.dtSeconds = 5;
        data.add("heap.used.pct_max", "", "", 40d);
        return data;
    }
}

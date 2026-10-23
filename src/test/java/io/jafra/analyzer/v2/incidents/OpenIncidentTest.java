package io.jafra.analyzer.v2.incidents;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;

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
class OpenIncidentTest {
    @Inject
    RollupWriter writer;

    @Inject
    V2Store store;

    @Inject
    JvmResolver jvms;

    @Test
    void openIncidentStaysVisibleWithNullEnd() throws Exception {
        String pod = "pod-" + System.nanoTime();
        writer.commitSynthetic("open-" + pod, window(pod));
        JvmRow jvm = jvms.resolve("v2open", pod, "app", null, null);
        String id = "open-" + pod;
        store.upsertIncident(new IncidentRecord(
                id,
                jvm.jvmId(),
                "long_pause",
                "gc",
                "critical",
                "open",
                2_000L,
                null,
                2_000L,
                90d,
                "medium",
                "pause",
                "felt",
                "{}",
                "{}",
                "{\"related\":[],\"groups\":[]}",
                "null",
                "[]",
                "[]",
                "{}",
                "[]"));

        String base = "/v2/jvms/v2open/" + pod + "/app";
        given()
                .queryParam("from", "1000")
                .queryParam("to", "10000")
                .get(base + "/incidents")
                .then()
                .statusCode(200)
                .body("incidents.find { it.id == '" + id + "' }.id", equalTo(id))
                .body("incidents.find { it.id == '" + id + "' }.t1", nullValue());

        given()
                .get(base + "/incidents/" + id)
                .then()
                .statusCode(200)
                .body("t1", nullValue())
                .body("when.t1", nullValue())
                .body("when.duration_s", greaterThan(0f));

        given()
                .queryParam("from", "1000")
                .queryParam("to", "10000")
                .get(base + "/timeline")
                .then()
                .statusCode(200)
                .body("incidents.id", hasItem(id));

        given()
                .queryParam("from", "1000")
                .queryParam("to", "10000")
                .get(base + "/report")
                .then()
                .statusCode(200)
                .body("groups.find { it.group == 'gc' }.open_incidents", hasItem(id));
    }

    private static WindowData window(String pod) {
        WindowData data = new WindowData();
        data.clusterId = "cluster-a";
        data.namespace = "v2open";
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
        data.add("gc.pause.time_pct", "", "", 1d);
        return data;
    }
}

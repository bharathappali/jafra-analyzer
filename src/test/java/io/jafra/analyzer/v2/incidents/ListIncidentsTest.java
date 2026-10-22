package io.jafra.analyzer.v2.incidents;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.api.JvmResolver;
import io.jafra.analyzer.v2.rollup.RollupWriter;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.jafra.analyzer.v2.store.V2Store;
import io.jafra.analyzer.v2.store.V2Store.IncidentRecord;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;

@QuarkusTest
class ListIncidentsTest {
    @Inject
    RollupWriter writer;

    @Inject
    V2Store store;

    @Inject
    JvmResolver jvms;

    @Test
    void listIsASummaryAndOmitsTotalsUntilTheCap() throws Exception {
        String pod = "pod-" + System.nanoTime();
        writer.commitSynthetic("list-" + pod, window(pod, 0L));
        JvmRow jvm = jvms.resolve("v2list", pod, "app", null, null);
        String open = "open-" + pod;
        String closed = "closed-" + pod;
        String later = "later-" + pod;
        store.upsertIncident(row(jvm.jvmId(), open, "open", 1_000L, null, "{\"related\":[{\"id\":\"" + closed + "\",\"type\":\"heap_near_full\"}],\"groups\":[]}"));
        store.upsertIncident(row(jvm.jvmId(), closed, "closed", 2_000L, 3_000L, "{\"related\":[],\"groups\":[]}"));
        store.upsertIncident(row(jvm.jvmId(), later, "open", 500_000L, null, "{\"related\":[],\"groups\":[]}"));

        String base = "/v2/jvms/v2list/" + pod + "/app/incidents";
        given().queryParam("from", "0").queryParam("to", "10000").get(base).then().statusCode(200).body("incidents", hasSize(2));
        given().queryParam("from", "0").queryParam("to", "10000").queryParam("status", "all").get(base)
                .then().statusCode(200).body("incidents", hasSize(2));
        given().queryParam("from", "0").queryParam("to", "10000").queryParam("status", "open").get(base)
                .then().statusCode(200).body("incidents", hasSize(1)).body("incidents[0].id", equalTo(open));

        JsonPath listed = given().queryParam("from", "0").queryParam("to", "10000").get(base).then().statusCode(200).extract().jsonPath();
        assertEquals(
                Set.of("id", "type", "group", "level", "status", "t0", "t1", "peak_at", "headline", "confidence", "related"),
                listed.getMap("incidents.find { it.id == '" + open + "' }").keySet());
        assertEquals(closed, listed.getString("incidents.find { it.id == '" + open + "' }.related[0].id"));
        assertEquals("heap_near_full", listed.getString("incidents.find { it.id == '" + open + "' }.related[0].type"));

        String many = "many-" + System.nanoTime();
        writer.commitSynthetic("many-" + many, window(many, 0L));
        JvmRow big = jvms.resolve("v2list", many, "app", null, null);
        for (int i = 0; i < 20; i++) {
            store.upsertIncident(row(big.jvmId(), "m20-" + i + "-" + many, "open", 1_000L + i, null, "{\"related\":[],\"groups\":[]}"));
        }
        given().queryParam("from", "0").queryParam("to", "100000").get("/v2/jvms/v2list/" + many + "/app/incidents")
                .then().statusCode(200).body("incidents", hasSize(20)).body("total", nullValue()).body("truncated", nullValue());
        store.upsertIncident(row(big.jvmId(), "m21-" + many, "open", 2_000L, null, "{\"related\":[],\"groups\":[]}"));
        given().queryParam("from", "0").queryParam("to", "100000").get("/v2/jvms/v2list/" + many + "/app/incidents")
                .then().statusCode(200).body("incidents", hasSize(20)).body("total", equalTo(21)).body("truncated", equalTo(true));

        given().get("/v2/jvms/v2list/" + pod + "/app/tools/get_incident")
                .then()
                .statusCode(404)
                .body("error", equalTo("unknown_incident"))
                .body("message", equalTo("id is required"))
                .body("hint", not(nullValue()))
                .body("valid", hasSize(3));
    }

    private static WindowData window(String pod, long from) {
        WindowData data = new WindowData();
        data.clusterId = "cluster-a";
        data.namespace = "v2list";
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

    private static IncidentRecord row(int jvmId, String id, String status, long t0, Long t1, String together) {
        return new IncidentRecord(
                id, jvmId, "long_pause", "gc", "warning", status, t0, t1, t0, 60d, "medium", "headline", "felt",
                "{}", "{}", together, "null", "[]", "[]", "{}", "[]");
    }
}

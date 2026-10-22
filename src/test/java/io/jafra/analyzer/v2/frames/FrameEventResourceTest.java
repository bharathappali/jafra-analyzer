package io.jafra.analyzer.v2.frames;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.rollup.RollupWriter;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class FrameEventResourceTest {
    @Inject
    RollupWriter writer;

    @Test
    void wilsonIntervalsDoNotOverlapForSeparatedShares() {
        assertTrue(FrameService.significant(200, 1d, 200, 0d));
        assertFalse(FrameService.significant(10, 1d, 200, 0d));
        assertFalse(FrameService.significant(200, 0.5d, 200, 0.5d));
    }

    @Test
    void flameFramesAndEvents() throws Exception {
        String pod = "pod-" + System.nanoTime();
        String leaf = "com.app.Leaf.m";
        String caller = "com.app.Caller.run";
        String jdk = "java.lang.Thread.run";
        WindowData first = window(pod, 0L);
        first.stack(1L, frames(leaf, "app", jdk, "jdk", caller, "app"));
        first.stack(3L, frames("tiny", "app"));
        first.sample("cpu", 1L, "main", "", "", 200, 200);
        first.sample("cpu", 3L, "main", "", "", 1, 1);
        first.event(new WindowData.StoredEvent(
                "error", 1000L, 50L, "main", null, null, "{\"message\":\"Nope\"}"));
        first.event(new WindowData.StoredEvent(
                "error", 2000L, 5L, "main", null, null, "{\"message\":\"other\"}"));
        first.event(new WindowData.StoredEvent(
                "gc.system_gc", 1500L, 100L, "main", "com.app.Main.gc", null, "{\"cause\":\"System.gc()\"}"));
        first.event(new WindowData.StoredEvent(
                "gc.evac_failed", 1600L, null, "main", null, null, "{\"gc_id\":7}"));
        first.event(new WindowData.StoredEvent(
                "gc.pause", 1600L, 9000L, "main", null, null, "{\"gc_id\":7,\"cause\":\"Evacuation\"}"));
        writer.commitSynthetic("frame-a-" + pod, first);

        WindowData second = window(pod, 5_000L);
        second.stack(2L, frames("com.app.Other.m", "app"));
        second.sample("cpu", 2L, "main", "", "", 200, 200);
        writer.commitSynthetic("frame-b-" + pod, second);

        String base = "/v2/jvms/v2frame/" + pod + "/app";
        given()
                .queryParam("from", "0")
                .queryParam("to", "5000")
                .get(base + "/flame/cpu")
                .then()
                .statusCode(200)
                .body("root.children", hasSize(1))
                .body("root.children[0].name", equalTo(caller))
                .body("root.children[0].category", equalTo("app"));

        given()
                .queryParam("state", "running")
                .queryParam("from", "0")
                .queryParam("to", "5000")
                .get(base + "/flame/cpu")
                .then()
                .statusCode(400)
                .body("error", equalTo("unsupported_by"))
                .body("valid", hasSize(1))
                .body("valid[0]", equalTo("wall"));

        given()
                .queryParam("kind", "cpu")
                .queryParam("endpoint", "Order")
                .queryParam("from", "0")
                .queryParam("to", "5000")
                .get(base + "/frames")
                .then()
                .statusCode(422)
                .body("error", equalTo("not_on_this_jdk"));

        given()
                .queryParam("kind", "cpu")
                .queryParam("from", "0")
                .queryParam("to", "5000")
                .get(base + "/frames")
                .then()
                .statusCode(200)
                .body("n", equalTo(201))
                .body("frames[0].frame", equalTo(leaf))
                .body("frames[0].app_caller", equalTo(caller))
                .body("frames.size()", org.hamcrest.Matchers.lessThanOrEqualTo(15));

        given()
                .queryParam("kind", "alloc")
                .queryParam("from", "0")
                .queryParam("to", "5000")
                .get(base + "/frames")
                .then()
                .statusCode(200)
                .body("flags", hasItem("low_confidence"));

        given()
                .queryParam("kind", "cpu")
                .queryParam("focus", leaf)
                .queryParam("from", "0")
                .queryParam("to", "5000")
                .get(base + "/frames")
                .then()
                .statusCode(200)
                .body("paths", hasSize(1))
                .body("paths[0].frames.size()", org.hamcrest.Matchers.lessThanOrEqualTo(8))
                .body("paths[0].frames.name", hasItem(caller));

        given()
                .queryParam("kind", "cpu")
                .queryParam("from", "0")
                .queryParam("to", "5000")
                .queryParam("vs_from", "5000")
                .queryParam("vs_to", "10000")
                .get(base + "/frames")
                .then()
                .statusCode(200)
                .body("frames.find { it.frame == '" + leaf + "' }.significant", equalTo(true))
                .body("frames.find { it.frame == '" + leaf + "' }.vs_share_pct", equalTo(0f));

        given()
                .queryParam("type", "error")
                .queryParam("sort", "duration")
                .queryParam("limit", "1")
                .queryParam("from", "0")
                .queryParam("to", "5000")
                .get(base + "/events")
                .then()
                .statusCode(200)
                .body("events", hasSize(1))
                .body("events[0].duration_us", equalTo(50));

        given()
                .queryParam("type", "error")
                .queryParam("where", "message=nope")
                .queryParam("from", "0")
                .queryParam("to", "5000")
                .get(base + "/events")
                .then()
                .statusCode(200)
                .body("events", hasSize(1))
                .body("events[0].fields.message", equalTo("Nope"));

        given()
                .queryParam("type", "gc.system_gc")
                .queryParam("from", "0")
                .queryParam("to", "5000")
                .get(base + "/events")
                .then()
                .statusCode(200)
                .body("events[0].top_frame", equalTo("com.app.Main.gc"));

        given()
                .queryParam("type", "gc.evac_failed")
                .queryParam("from", "0")
                .queryParam("to", "5000")
                .get(base + "/events")
                .then()
                .statusCode(200)
                .body("events[0].fields.pause_us", equalTo(9000));

        given()
                .queryParam("type", "not-an-event")
                .queryParam("from", "0")
                .queryParam("to", "5000")
                .get(base + "/events")
                .then()
                .statusCode(400)
                .body("valid", hasSize(19));
    }

    private static String frames(String... nameAndCategory) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < nameAndCategory.length; i += 2) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append("{\"frame\":\"").append(nameAndCategory[i]).append("\",\"category\":\"")
                    .append(nameAndCategory[i + 1]).append("\",\"type\":\"Java\",\"line\":1}");
        }
        return builder.append(']').toString();
    }

    private static WindowData window(String pod, long from) {
        WindowData data = new WindowData();
        data.clusterId = "cluster-a";
        data.namespace = "v2frame";
        data.podName = pod;
        data.podUid = "uid";
        data.containerName = "app";
        data.jvmStartMs = 8_000L;
        data.jdkMajor = 21;
        data.jdkVersion = "21.0.0";
        data.profile = "aggressive";
        data.settingsJson = "{}";
        data.fromMs = from;
        data.toMs = from + 5_000;
        data.dtSeconds = 5;
        data.add("heap.used.bytes", "", "", 1d);
        return data;
    }
}

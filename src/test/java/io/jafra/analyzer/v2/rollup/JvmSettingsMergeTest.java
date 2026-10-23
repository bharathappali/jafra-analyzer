package io.jafra.analyzer.v2.rollup;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class JvmSettingsMergeTest {
    @Inject
    RollupWriter writer;

    @Test
    void laterChunkWithoutJvmInformationKeepsSettings() throws Exception {
        String pod = "pod-" + System.nanoTime();
        WindowData first = window(pod, 0L);
        first.jdkMajor = 21;
        first.jdkVersion = "21.0.2";
        first.profile = "aggressive";
        first.jvmStartMs = 1_704_170_645_000L;
        first.settingsJson = """
                {"jvm":{"version":"21.0.2","start":"2024-01-02T03:04:05.000Z","name":"OpenJDK 64-Bit Server VM"},
                 "heap":{"max":1073741824,"initial":536870912},
                 "gc":{"young":"G1New","old":"G1Old"}}
                """;
        WindowData second = window(pod, 5_000L);
        second.jdkMajor = 0;
        second.jdkVersion = "";
        second.profile = "custom";
        second.jvmStartMs = first.jvmStartMs;
        second.settingsJson = """
                {"jvm":{"version":"","name":"","start":"1970-01-01T00:00:00Z","arguments":""},
                 "heap":{"initial":null,"max":null},
                 "gc":{"young":null,"old":null}}
                """;
        writer.commitSynthetic("jvm-a-" + pod, first);
        writer.commitSynthetic("jvm-b-" + pod, second);

        given()
                .get("/v2/jvms/v2jvm/" + pod + "/app/tools/get_jvm_info")
                .then()
                .statusCode(200)
                .body("jvm.version", equalTo("21.0.2"))
                .body("jvm.start", equalTo("2024-01-02T03:04:05.000Z"))
                .body("gc.young", equalTo("G1New"))
                .body("gc.old", equalTo("G1Old"))
                .body("heap.max", equalTo(1073741824))
                .body("heap.initial", equalTo(536870912));
    }

    private static WindowData window(String pod, long from) {
        WindowData data = new WindowData();
        data.clusterId = "cluster-a";
        data.namespace = "v2jvm";
        data.podName = pod;
        data.podUid = "uid";
        data.containerName = "app";
        data.settingsJson = "{}";
        data.fromMs = from;
        data.toMs = from + 5_000L;
        data.dtSeconds = 5;
        data.add("heap.used.pct_max", "", "", 40d);
        return data;
    }
}

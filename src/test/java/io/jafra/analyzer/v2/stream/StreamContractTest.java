package io.jafra.analyzer.v2.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.rollup.RollupWriter;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;

@QuarkusTest
class StreamContractTest {
    @Inject
    RollupWriter writer;

    @Test
    void windowEventCarriesNsJdkAndId() throws Exception {
        String pod = "pod-" + System.nanoTime();
        WindowData data = new WindowData();
        data.clusterId = "cluster-a";
        data.namespace = "v2sse";
        data.podName = pod;
        data.podUid = "uid";
        data.containerName = "app";
        data.jvmStartMs = 4_000L;
        data.jdkMajor = 21;
        data.jdkVersion = "21.0.0";
        data.profile = "low";
        data.settingsJson = "{}";
        data.fromMs = 7_000L;
        data.toMs = 12_000L;
        data.dtSeconds = 5;
        data.add("heap.used.pct_max", "", "", 10d);
        writer.commitSynthetic("sse-" + pod, data);

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + RestAssured.port + "/v2/jvms/v2sse/" + pod + "/app/stream"))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "text/event-stream")
                .header("Last-Event-ID", "0")
                .GET()
                .build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("no", response.headers().firstValue("X-Accel-Buffering").orElse(""));
        byte[] buf = new byte[8192];
        StringBuilder text = new StringBuilder();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        try (java.io.InputStream in = response.body()) {
            while (System.nanoTime() < deadline && !text.toString().contains("\"jdk\"")) {
                if (in.available() == 0) {
                    Thread.sleep(50);
                    continue;
                }
                int n = in.read(buf);
                if (n < 0) {
                    break;
                }
                text.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }
        String body = text.toString();
        assertTrue(body.contains("id:7000") || body.contains("id: 7000"), body);
        assertTrue(body.contains("\"ns\":\"v2sse\""), body);
        assertTrue(body.contains("\"jdk\":21"), body);
        assertTrue(body.contains("\"cluster\":\"cluster-a\""), body);
    }
}

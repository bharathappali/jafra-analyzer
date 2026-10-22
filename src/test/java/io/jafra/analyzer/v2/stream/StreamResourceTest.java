package io.jafra.analyzer.v2.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
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
class StreamResourceTest {
    @Inject
    RollupWriter writer;

    @Inject
    StreamHub hub;

    @Test
    void streamEmitsWindowAndHeartbeatAndDropsTheSubscriber() throws Exception {
        String pod = "pod-" + System.nanoTime();
        writer.commitSynthetic("seed-" + pod, window(pod, 0));
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + RestAssured.port + "/v2/jvms/v2sse/" + pod + "/app/stream"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertEquals("no", response.headers().firstValue("X-Accel-Buffering").orElse(""));
        try (InputStream body = response.body()) {
            writer.commitSynthetic("next-" + pod, window(pod, 5_000));
            String text = readUntil(body, Duration.ofSeconds(8));
            assertTrue(text.contains("event:window"), text);
            assertTrue(text.contains("event:heartbeat"), text);
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (hub.subscribers() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(0, hub.subscribers());
    }

    private static String readUntil(InputStream body, Duration timeout) throws Exception {
        StringBuilder text = new StringBuilder();
        Thread reader = new Thread(() -> {
            byte[] buffer = new byte[1024];
            try {
                while (!(text.indexOf("event:heartbeat") >= 0 && text.indexOf("event:window") >= 0)) {
                    int read = body.read(buffer);
                    if (read < 0) {
                        return;
                    }
                    text.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
                // the caller closes the stream when the wait is over
            }
        });
        reader.setDaemon(true);
        reader.start();
        reader.join(timeout.toMillis());
        return text.toString();
    }

    private static WindowData window(String pod, long from) {
        WindowData data = new WindowData();
        data.clusterId = "cluster-a";
        data.namespace = "v2sse";
        data.podName = pod;
        data.podUid = "uid";
        data.containerName = "app";
        data.jvmStartMs = 12_000L;
        data.jdkMajor = 21;
        data.jdkVersion = "21.0.0";
        data.profile = "aggressive";
        data.settingsJson = "{}";
        data.fromMs = from;
        data.toMs = from + 5_000;
        data.dtSeconds = 5;
        data.add("heap.used.bytes", "", "", 10);
        return data;
    }
}

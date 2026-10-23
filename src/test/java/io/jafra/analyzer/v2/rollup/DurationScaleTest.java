package io.jafra.analyzer.v2.rollup;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.rollup.extract.Extractors;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

@QuarkusTest
class DurationScaleTest {
    @Inject
    RollupWriter writer;

    @Test
    void monitorAndSocketDurationsStayInMicroseconds() throws Exception {
        Path dump = Files.createTempFile("jafra-duration", ".jfr");
        long start = System.currentTimeMillis() - 1_000L;
        Object lock = new Object();
        try (Recording recording = new Recording()) {
            recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(1));
            recording.enable("jdk.SocketRead").withThreshold(Duration.ofMillis(1));
            recording.start();
            Thread holder = new Thread(() -> {
                synchronized (lock) {
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            holder.start();
            Thread.sleep(10);
            synchronized (lock) {
                // wait out the holder
            }
            holder.join();
            try (ServerSocket server = new ServerSocket(0)) {
                Thread peer = new Thread(() -> {
                    try (Socket accepted = server.accept()) {
                        Thread.sleep(25);
                        accepted.getOutputStream().write(1);
                        accepted.getOutputStream().flush();
                    } catch (Exception ignored) {
                        // the client side asserts that a read was recorded
                    }
                });
                peer.start();
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()));
                    socket.setSoTimeout(2_000);
                    socket.getInputStream().read();
                }
                peer.join();
            }
            recording.dump(dump);
        }
        long end = start + 15_000L;
        double monitorUs = 0;
        double socketUs = 0;
        try (RecordingFile file = new RecordingFile(dump)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (event.getDuration() == null) {
                    continue;
                }
                double us = event.getDuration().toNanos() / 1000d;
                String name = event.getEventType().getName();
                if ("jdk.JavaMonitorEnter".equals(name)) {
                    monitorUs += us;
                } else if ("jdk.SocketRead".equals(name)) {
                    socketUs += us;
                }
            }
        }
        assertTrue(monitorUs > 1_000d, "expected a monitor-enter duration");
        assertTrue(socketUs > 1_000d, "expected a socket-read duration");
        ChunkFacts facts;
        try (RecordingFile file = new RecordingFile(dump)) {
            facts = new ChunkReader(10d).read(file, start, end);
        }
        double copiedMonitor = facts.ofType("jdk.JavaMonitorEnter").stream()
                .mapToDouble(event -> event.num("duration") == null ? 0d : event.num("duration"))
                .sum();
        double copiedSocket = facts.ofType("jdk.SocketRead").stream()
                .mapToDouble(event -> event.num("duration") == null ? 0d : event.num("duration"))
                .sum();
        assertEquals(monitorUs, copiedMonitor, monitorUs * 0.01d);
        assertEquals(socketUs, copiedSocket, socketUs * 0.01d);

        WindowData out = new WindowData();
        out.dtSeconds = 5;
        Extractors.extract(facts, out);
        String pod = "pod-" + System.nanoTime();
        out.clusterId = "cluster-a";
        out.namespace = "v2unit";
        out.podName = pod;
        out.podUid = "uid";
        out.containerName = "app";
        out.jvmStartMs = 11_000L;
        out.jdkMajor = 21;
        out.jdkVersion = "21.0.0";
        out.profile = "low";
        out.settingsJson = "{}";
        out.fromMs = 0L;
        out.toMs = 5_000L;
        writer.commitSynthetic("dur-" + pod, out);

        given()
                .queryParam("metric", "locks.wait.ms_per_s")
                .queryParam("from", "0")
                .queryParam("to", "5000")
                .get("/v2/jvms/v2unit/" + pod + "/app/query")
                .then()
                .statusCode(200)
                .body("unit", org.hamcrest.Matchers.equalTo("ms_per_s"))
                .body("value", greaterThan(0f))
                .body("value", lessThan(1_000f));

        given()
                .queryParam("metric", "socket.read.us")
                .queryParam("stat", "p99")
                .queryParam("from", "0")
                .queryParam("to", "5000")
                .get("/v2/jvms/v2unit/" + pod + "/app/query")
                .then()
                .statusCode(200)
                .body("unit", org.hamcrest.Matchers.equalTo("us"))
                .body("value", greaterThan(1_000f))
                .body("value", lessThan(1_000_000f));
        Files.deleteIfExists(dump);
    }
}

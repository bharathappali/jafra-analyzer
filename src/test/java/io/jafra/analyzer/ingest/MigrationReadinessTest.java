package io.jafra.analyzer.ingest;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.jafra.analyzer.storage.ChunkMetadata;
import io.jafra.analyzer.storage.ChunkStore;
import io.jafra.analyzer.storage.MigrationPause;
import io.jafra.ingest.v1.AckStatus;
import io.jafra.ingest.v1.OpenChunk;
import io.jafra.ingest.v1.UploadRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

@QuarkusTest
@TestProfile(MigrationReadinessTest.SlowMigrationProfile.class)
class MigrationReadinessTest {
    @Inject
    ChunkStore store;

    @Inject
    IngestRegistry registry;

    @AfterAll
    static void releasePause() {
        SlowMigrationProfile.release.countDown();
        MigrationPause.clear();
    }

    @Test
    void slowMigrationKeepsReadsDownUntilItFinishes() throws Exception {
        assertTrue(SlowMigrationProfile.entered.await(20, TimeUnit.SECONDS), "migration did not start");
        assertFalse(store.ready());

        given().when().get("/q/health/live").then().statusCode(200);
        given().when().get("/q/health/ready").then().statusCode(503);
        given().when().get("/api/v1/recordings").then().statusCode(503).header("Retry-After", "30");

        IngestRegistry.StreamContext context = registry.openStream();
        var ack = registry.handle(context, UploadRequest.newBuilder().setOpen(open()).build());
        registry.closeStream(context);
        assertEquals(AckStatus.RETRY, ack.getStatus());

        SlowMigrationProfile.release.countDown();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!store.ready() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(store.ready());
        given().when().get("/q/health/ready").then().statusCode(200);
        given().when().get("/api/v1/recordings").then().statusCode(200);
    }

    private static OpenChunk open() {
        return OpenChunk.newBuilder()
                .setProtocolVersion(1)
                .setClusterId("default")
                .setNamespace("ns")
                .setPodUid("uid")
                .setPodName("pod")
                .setContainerName("app")
                .setPhysicalFilename("profile.jfr")
                .setRecordingId("rec-slow")
                .setChunkOffset(0)
                .setChunkLength(4)
                .build();
    }

    public static class SlowMigrationProfile implements QuarkusTestProfile {
        static final CountDownLatch entered = new CountDownLatch(1);
        static final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Map<String, String> getConfigOverrides() {
            try {
                Path root = Files.createTempDirectory("jafra-slow-migration");
                byte[] payload = new byte[] {1, 2, 3, 4};
                ChunkMetadata metadata = new ChunkMetadata(
                        "slow-chunk",
                        "rec-slow",
                        "default",
                        "ns",
                        "uid",
                        "pod",
                        "app",
                        "profile.jfr",
                        0,
                        payload.length,
                        "checksum",
                        1_000_000_000L,
                        10);
                Path chunks = root.resolve("chunks");
                Files.createDirectories(chunks);
                Files.write(chunks.resolve("slow-chunk.jfr"), payload);
                new ObjectMapper().writeValue(chunks.resolve("slow-chunk.meta").toFile(), metadata);
                MigrationPause.arm(entered, release);
                return Map.of("jafra.storage.root", root.toAbsolutePath().toString());
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }
    }
}

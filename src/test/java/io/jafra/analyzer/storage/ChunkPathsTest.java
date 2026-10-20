package io.jafra.analyzer.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ChunkPathsTest {
    @Test
    void similarPodNamesLandInDifferentFolders() {
        String payments = ChunkPaths.relative(metadata("payments-api", "default", "ns", "pod-1"));
        String gateway = ChunkPaths.relative(metadata("pay-gateway-api", "default", "ns", "pod-1"));
        assertNotEquals(Path.of(payments).getParent(), Path.of(gateway).getParent());
        assertTrue(payments.contains("/payments-api/"));
        assertTrue(gateway.contains("/pay-gateway-api/"));
    }

    @Test
    void fileNameUsesFlooredStartAndCeiledEnd() {
        ChunkMetadata metadata = new ChunkMetadata(
                "chunk-a",
                "rec",
                "",
                "ns",
                "pod-uid",
                "payments-api",
                "app",
                "profile.jfr",
                0,
                10,
                "checksum",
                1_750_000_000_000_000_000L,
                5_000_000_000L);
        String relative = ChunkPaths.relative(metadata);
        assertTrue(relative.startsWith("default/ns/payments-api/app/chunks/pod-uid/default/"));
        assertTrue(relative.endsWith("/684ee180-684ee185-chunk-a.jfr"));
        assertEquals(
                Path.of("data/recordings").resolve(relative),
                Path.of("root").relativize(ChunkPaths.payload(Path.of("root"), metadata)));
    }

    @Test
    void badComponentsAreRejected() {
        assertThrows(InvalidIdentityException.class, () -> ChunkPaths.relative(metadata("", "ns", "pod-1", "uid")));
        assertThrows(InvalidIdentityException.class, () -> ChunkPaths.relative(metadata("pod", "ns", "../pod", "uid")));
        assertThrows(InvalidIdentityException.class, () -> ChunkPaths.relative(metadata("pod", "n/s", "pod", "uid")));
        assertThrows(InvalidIdentityException.class, () -> ChunkPaths.relative(metadata("pod", "ns", "pod", "uid\\x")));
        assertThrows(InvalidIdentityException.class, () -> ChunkPaths.relative(metadata("pod", "ns", "po\u0001d", "uid")));
        assertThrows(InvalidIdentityException.class, () -> ChunkPaths.relative(metadata("pod", "ns", "pod", "")));
    }

    private static ChunkMetadata metadata(String pod, String namespace, String podUid, String container) {
        return new ChunkMetadata(
                "chunk-a",
                "rec",
                "cluster",
                namespace,
                podUid,
                pod,
                container,
                "profile.jfr",
                0,
                10,
                "checksum",
                0,
                0);
    }
}

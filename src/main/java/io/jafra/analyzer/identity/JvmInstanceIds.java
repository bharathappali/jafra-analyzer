package io.jafra.analyzer.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** JVM instance ids are the first 16 hex characters of a SHA-256. */
public final class JvmInstanceIds {
    private JvmInstanceIds() {}

    public static String of(
            String namespace, String podName, String containerName, String podUid, long jvmStartTimeMs, long pid) {
        String material = value(namespace) + '|' + value(podName) + '|' + value(containerName) + '|' + value(podUid)
                + '|' + jvmStartTimeMs + '|' + pid;
        return first16(material);
    }

    public static String provisional(String recordingId) {
        return first16("provisional|" + value(recordingId));
    }

    private static String first16(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required", error);
        }
    }

    private static String value(String raw) {
        return raw == null ? "" : raw;
    }
}

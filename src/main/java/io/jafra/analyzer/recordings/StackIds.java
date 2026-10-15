package io.jafra.analyzer.recordings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local stack catalog. {@code stack_id} is the first 8 bytes of SHA-256 over the top 64
 * frames (method, line, type). A later lookup in this process resolves the id; a restart forgets it.
 */
public final class StackIds {
    private static final int ID_FRAMES = 64;
    private static final int MATCH_FRAMES = 8;
    private static final ConcurrentHashMap<String, Identity> CATALOG = new ConcurrentHashMap<>();

    private StackIds() {}

    public static Identity intern(List<Frame> frames) {
        if (frames == null || frames.isEmpty()) {
            return null;
        }
        String stackId = hash(frames, ID_FRAMES);
        return CATALOG.computeIfAbsent(stackId, ignored -> identify(stackId, frames));
    }

    /** Same 16-hex id as {@link #intern}, without storing the stack in the process catalog. */
    public static String idOf(List<Frame> frames) {
        if (frames == null || frames.isEmpty()) {
            return null;
        }
        return hash(frames, ID_FRAMES);
    }

    public static boolean platformClass(String className) {
        return isPlatform(className);
    }

    public static Identity get(String stackId) {
        if (stackId == null || stackId.isBlank()) {
            return null;
        }
        return CATALOG.get(stackId);
    }

    private static Identity identify(String stackId, List<Frame> frames) {
        Frame leafFrame = frames.get(0);
        String leaf = leafFrame.method();
        String topApp = leaf;
        for (Frame frame : frames) {
            if (!isPlatform(frame.className())) {
                topApp = frame.method();
                break;
            }
        }
        String matchKey = topApp.equals(leaf) ? hash(frames, MATCH_FRAMES) : topApp;
        return new Identity(stackId, leaf, topApp, frames.size(), matchKey, List.copyOf(frames));
    }

    private static boolean isPlatform(String className) {
        if (className == null || className.isBlank()) {
            return true;
        }
        return className.startsWith("java.") || className.startsWith("jdk.") || className.startsWith("sun.");
    }

    private static String hash(List<Frame> frames, int limit) {
        StringBuilder canonical = new StringBuilder();
        int count = Math.min(limit, frames.size());
        for (int i = 0; i < count; i++) {
            Frame frame = frames.get(i);
            canonical.append(frame.method()).append('\t').append(frame.line()).append('\t').append(frame.type()).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            long value = 0L;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (digest[i] & 0xffL);
            }
            String hex = Long.toUnsignedString(value, 16);
            return "0".repeat(16 - hex.length()) + hex;
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required", error);
        }
    }

    public record Frame(String method, String className, int line, String type) {}

    public record Identity(
            String stackId, String leaf, String topAppFrame, int depth, String matchKey, List<Frame> frames) {

        public List<Frame> frames(int depthLimit) {
            int limit = Math.min(Math.max(depthLimit, 0), frames.size());
            return new ArrayList<>(frames.subList(0, limit));
        }
    }
}

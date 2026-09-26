package io.jafra.analyzer.storage;

import java.util.Locale;

/**
 * Shared file name for a chunk's JFR bytes and its meta file.
 *
 * <p>The name is {@code <60-char search>-<chunk id>}. The search block is fixed width so a query
 * can match time, namespace, pod, and container by slicing the name. The chunk id after the hyphen
 * stays the duplicate key.
 */
public final class ChunkFileName {
    public static final int SEARCH_CHARS = 60;
    public static final long BUCKET_SECONDS = 4L * 60L * 60L;

    private static final int START_AT = 0;
    private static final int END_AT = 8;
    private static final int NAMESPACE_AT = 16;
    private static final int POD_AT = 28;
    private static final int UID_AT = 40;
    private static final int CONTAINER_AT = 48;
    private static final int WORD_CHARS = 12;
    private static final int UID_CHARS = 8;

    private ChunkFileName() {}

    public static String fileName(ChunkMetadata metadata) {
        return search(metadata) + "-" + metadata.chunkId();
    }

    public static String bucket(ChunkMetadata metadata) {
        return bucket(startSeconds(metadata.chunkStartTimeNs()));
    }

    public static String bucket(long startSeconds) {
        long safe = Math.max(0, startSeconds);
        return hex8(safe / BUCKET_SECONDS);
    }

    public static Parsed parse(String fileName) {
        if (fileName == null || fileName.length() < SEARCH_CHARS + 2 || fileName.charAt(SEARCH_CHARS) != '-') {
            return null;
        }
        String search = fileName.substring(0, SEARCH_CHARS);
        if (!isSearch(search)) {
            return null;
        }
        String chunkId = fileName.substring(SEARCH_CHARS + 1);
        if (chunkId.isBlank() || chunkId.indexOf('/') >= 0 || chunkId.indexOf('\\') >= 0) {
            return null;
        }
        return new Parsed(
                fileName,
                search,
                chunkId,
                hexSeconds(search, START_AT),
                hexSeconds(search, END_AT),
                search.substring(NAMESPACE_AT, NAMESPACE_AT + WORD_CHARS),
                search.substring(POD_AT, POD_AT + WORD_CHARS),
                search.substring(UID_AT, UID_AT + UID_CHARS),
                search.substring(CONTAINER_AT, CONTAINER_AT + WORD_CHARS));
    }

    /**
     * A file can match a query before its meta is opened. An empty pod block still matches a pod
     * query because the pod name is sometimes learned after the chunk is stored.
     */
    public static boolean allows(Parsed parsed, String namespace, String pod, String container) {
        if (parsed == null) {
            return true;
        }
        if (!blockMatches(namespace, parsed.namespaceBlock())) {
            return false;
        }
        if (!blockMatches(container, parsed.containerBlock())) {
            return false;
        }
        if (pod != null && !pod.isBlank()) {
            String wanted = encodeWord(pod);
            if (!parsed.podBlock().equals(wanted) && !parsed.podBlock().equals(encodeWord(""))) {
                return false;
            }
        }
        return true;
    }

    static String search(ChunkMetadata metadata) {
        String encoded = hex8(startSeconds(metadata.chunkStartTimeNs()))
                + hex8(endSeconds(metadata.chunkStartTimeNs(), metadata.chunkDurationNs()))
                + encodeWord(metadata.namespace())
                + encodeWord(metadata.podName())
                + encodeUid(metadata.podUid())
                + encodeWord(metadata.containerName());
        if (encoded.length() != SEARCH_CHARS) {
            throw new IllegalStateException("search block length " + encoded.length());
        }
        return encoded;
    }

    static long startSeconds(long startNs) {
        if (startNs <= 0) {
            return 0;
        }
        return startNs / 1_000_000_000L;
    }

    static long endSeconds(long startNs, long durationNs) {
        if (startNs <= 0) {
            return 0;
        }
        long endNs = startNs + Math.max(0, durationNs);
        if (endNs <= startNs) {
            return startSeconds(startNs);
        }
        return (endNs + 1_000_000_000L - 1) / 1_000_000_000L;
    }

    static String encodeWord(String value) {
        String word = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return hexChars(edge(word)) + hexChars(edge(word.length() >= 3 ? word.substring(word.length() - 3) : word));
    }

    static String encodeUid(String podUid) {
        String uid = podUid == null ? "" : podUid.toLowerCase(Locale.ROOT);
        if (uid.length() >= UID_CHARS) {
            return uid.substring(0, UID_CHARS);
        }
        return uid + "_".repeat(UID_CHARS - uid.length());
    }

    private static String edge(String value) {
        if (value.length() >= 3) {
            return value.substring(0, 3);
        }
        return value + "_".repeat(3 - value.length());
    }

    private static String hexChars(String three) {
        StringBuilder encoded = new StringBuilder(6);
        for (int i = 0; i < 3; i++) {
            encoded.append(String.format("%02x", (int) three.charAt(i) & 0xff));
        }
        return encoded.toString();
    }

    private static boolean blockMatches(String expected, String actual) {
        return expected == null || expected.isBlank() || encodeWord(expected).equals(actual);
    }

    private static boolean isSearch(String search) {
        for (int i = 0; i < SEARCH_CHARS; i++) {
            char c = search.charAt(i);
            boolean uid = i >= UID_AT && i < UID_AT + UID_CHARS;
            if (uid) {
                boolean letter = c >= 'a' && c <= 'z';
                if (!(isHex(c) || letter || c == '_' || c == '-')) {
                    return false;
                }
            } else if (!isHex(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    private static long hexSeconds(String search, int at) {
        return Long.parseUnsignedLong(search.substring(at, at + 8), 16);
    }

    private static String hex8(long value) {
        long safe = value;
        if (safe < 0) {
            safe = 0;
        }
        if (safe > 0xffffffffL) {
            safe = 0xffffffffL;
        }
        return String.format("%08x", safe);
    }

    public record Parsed(
            String fileName,
            String search,
            String chunkId,
            long startSeconds,
            long endSeconds,
            String namespaceBlock,
            String podBlock,
            String uidBlock,
            String containerBlock) {}
}

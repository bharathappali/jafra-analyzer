package io.jafra.analyzer.ingest.decode;

/** Thread identity stored on sample and duration rows. */
public final class ThreadKeys {
    private ThreadKeys() {}

    public static long osThreadId(long osThreadId, long javaThreadId) {
        return osThreadId > 0L ? osThreadId : javaThreadId;
    }

    /** Trailing digits become a single star so pooled threads share a group. */
    public static String group(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return name.replaceFirst("\\d+$", "*");
    }
}

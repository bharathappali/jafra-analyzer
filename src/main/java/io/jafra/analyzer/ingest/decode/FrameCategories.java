package io.jafra.analyzer.ingest.decode;

/** Frame category for the v2 stack table. */
public final class FrameCategories {
    private static final String[] FRAMEWORK = {
        "io.quarkus.", "org.springframework.", "io.netty.", "io.vertx.", "org.hibernate.", "com.zaxxer."
    };

    private FrameCategories() {}

    public static String of(String className, String method, String threadName) {
        if (className == null || className.isBlank()) {
            return nativeCategory(method, threadName);
        }
        if (starts(className, "profiler.", "one.profiler.", "asprof.")) {
            return "profiler";
        }
        for (String prefix : FRAMEWORK) {
            if (className.startsWith(prefix)) {
                return "framework";
            }
        }
        if (starts(className, "java.", "jdk.", "sun.")) {
            return "jdk";
        }
        return "app";
    }

    private static String nativeCategory(String method, String threadName) {
        String blob = ((method == null ? "" : method) + " " + (threadName == null ? "" : threadName)).toLowerCase();
        if (blob.contains("gc") || blob.contains("g1 ") || blob.contains("gang worker")) {
            return "gc";
        }
        if (blob.contains("compiler") || blob.contains("compilebroker") || blob.contains("c1 ") || blob.contains("c2 ")
                || blob.contains("jit")) {
            return "jit";
        }
        if (blob.contains("vm thread") || blob.contains("vm periodic") || blob.contains("service thread")) {
            return "vm";
        }
        return "native";
    }

    private static boolean starts(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

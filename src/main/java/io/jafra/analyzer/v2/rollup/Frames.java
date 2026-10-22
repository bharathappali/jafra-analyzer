package io.jafra.analyzer.v2.rollup;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Frames {
    public static final int MAX_DEPTH = 64;

    private static final Set<String> JDK_PREFIXES = Set.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "jakarta.");

    private Frames() {}

    public static String category(String type, String name) {
        String kind = type == null ? "" : type.trim();
        if (kind.equalsIgnoreCase("Native") || kind.equalsIgnoreCase("Kernel")) {
            return "native";
        }
        if (kind.equalsIgnoreCase("C++") || kind.equalsIgnoreCase("CPP")) {
            return "jvm";
        }
        String frame = name == null ? "" : name;
        for (String prefix : JDK_PREFIXES) {
            if (frame.startsWith(prefix)) {
                return "jdk";
            }
        }
        return "app";
    }

    /** Nearest app frame on the caller side of {@code index}. Callee is index 0. */
    public static String appCaller(List<Frame> frames, int index) {
        if (frames == null) {
            return null;
        }
        for (int i = index + 1; i < frames.size(); i++) {
            if ("app".equals(frames.get(i).category())) {
                return frames.get(i).name();
            }
        }
        return null;
    }

    public static String firstApp(List<Frame> frames) {
        if (frames == null) {
            return null;
        }
        for (Frame frame : frames) {
            if ("app".equals(frame.category())) {
                return frame.name();
            }
        }
        return null;
    }

    /** First frame that is not a JDK frame. Used as an allocation site. */
    public static String firstNonJdk(List<Frame> frames) {
        if (frames == null) {
            return null;
        }
        for (Frame frame : frames) {
            if (!"jdk".equals(frame.category())) {
                return frame.name();
            }
        }
        return frames.isEmpty() ? null : frames.get(0).name();
    }

    public static String top(List<Frame> frames) {
        if (frames == null || frames.isEmpty()) {
            return null;
        }
        return frames.get(0).name();
    }

    public static long hash(List<Frame> frames) {
        long value = 0xcbf29ce484222325L;
        if (frames == null) {
            return value;
        }
        for (Frame frame : frames) {
            value = mix(value, frame.name());
            value = mix(value, frame.category());
            value = mix(value, frame.type());
            value ^= frame.line();
            value *= 0x100000001b3L;
        }
        return value;
    }

    public static String pool(String thread) {
        if (thread == null || thread.isBlank()) {
            return "";
        }
        int dash = thread.indexOf('-');
        if (dash <= 0) {
            return thread.toLowerCase(Locale.ROOT);
        }
        return thread.substring(0, dash);
    }

    private static long mix(long hash, String text) {
        long value = hash;
        String raw = text == null ? "" : text;
        for (int i = 0; i < raw.length(); i++) {
            value ^= raw.charAt(i);
            value *= 0x100000001b3L;
        }
        value ^= 0xff;
        value *= 0x100000001b3L;
        return value;
    }
}

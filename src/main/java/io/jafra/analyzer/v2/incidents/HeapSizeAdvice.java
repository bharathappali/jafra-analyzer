package io.jafra.analyzer.v2.incidents;

import com.fasterxml.jackson.databind.JsonNode;

/** Shared heap-size todo. Flags come from jvmArguments. JAVA_TOOL_OPTIONS is unverified. */
public final class HeapSizeAdvice {
    private HeapSizeAdvice() {}

    public static String todo(JsonNode settings, double heapMaxBytes, Double containerUsagePct, boolean containerHot) {
        String arguments = settings == null ? "" : settings.path("jvm").path("arguments").asText("");
        boolean xmx = arguments.contains("-Xmx") || arguments.contains("-XX:MaxHeapSize");
        String max = bytes(heapMaxBytes);
        if (containerHot) {
            String limit = settings == null ? "" : settings.path("container").path("memory_limit").asText("");
            return "raise the pod memory limit " + (limit.isBlank() ? "(no pod limit was recorded)" : limit)
                    + " first: metaspace, threads and direct buffers need room outside the heap";
        }
        if (!xmx) {
            String percent = flag(arguments, "-XX:MaxRAMPercentage");
            if (percent == null) {
                percent = "25";
            }
            String limit = settings == null ? "" : settings.path("container").path("memory_limit").asText("");
            String limitText = limit.isBlank() ? "no pod memory limit was recorded" : "pod memory limit " + limit;
            return "raise -XX:MaxRAMPercentage from " + percent + "% (" + limitText + ", heap max now " + max
                    + "). Flag source jvmArguments; JAVA_TOOL_OPTIONS was not read";
        }
        return "raise -Xmx from " + max + ". Flag source jvmArguments; JAVA_TOOL_OPTIONS was not read";
    }

    private static String flag(String arguments, String name) {
        int at = arguments.indexOf(name);
        if (at < 0) {
            return null;
        }
        int eq = arguments.indexOf('=', at);
        if (eq < 0) {
            return null;
        }
        int end = eq + 1;
        while (end < arguments.length() && !Character.isWhitespace(arguments.charAt(end))) {
            end++;
        }
        String value = arguments.substring(eq + 1, end).trim();
        return value.isEmpty() ? null : value;
    }

    private static String bytes(double value) {
        if (value >= 1_073_741_824d) {
            return Math.round(value / 1_073_741_824d) + "g";
        }
        if (value >= 1_048_576d) {
            return Math.round(value / 1_048_576d) + "m";
        }
        return Math.round(value) + "b";
    }
}

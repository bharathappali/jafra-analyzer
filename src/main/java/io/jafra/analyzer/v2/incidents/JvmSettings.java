package io.jafra.analyzer.v2.incidents;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jafra.analyzer.v2.store.V2Store.JvmRow;

/** Reads the stored get_jvm_info payload. Flag values come from jvmArguments. */
public final class JvmSettings {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final int jdk;
    private final String profile;
    private final JsonNode raw;

    public JvmSettings(JvmRow jvm) {
        this.jdk = jvm == null ? 0 : jvm.jdkMajor();
        this.profile = jvm == null || jvm.profile() == null ? "" : jvm.profile();
        this.raw = parse(jvm == null ? null : jvm.settings());
    }

    public int jdk() {
        return jdk;
    }

    public String profile() {
        return profile;
    }

    public JsonNode raw() {
        return raw;
    }

    public String jvmArguments() {
        return raw.path("jvm").path("arguments").asText("");
    }

    /**
     * Value of {@code -XX:Flag=value}, {@code -Xmx<v>}, or a present {@code -XX:+Flag}.
     * JAVA_TOOL_OPTIONS is not read.
     */
    public Optional<String> argument(String flag) {
        if (flag == null || flag.isBlank()) {
            return Optional.empty();
        }
        String arguments = jvmArguments();
        if (arguments.isBlank()) {
            return Optional.empty();
        }
        for (String token : arguments.split("\\s+")) {
            if (token.equals(flag)) {
                return Optional.of(token.startsWith("-XX:-") ? "false" : "true");
            }
            if (token.startsWith(flag + "=")) {
                String value = token.substring(flag.length() + 1);
                return value.isEmpty() ? Optional.empty() : Optional.of(value);
            }
            if ("-Xmx".equals(flag) && token.startsWith("-Xmx") && token.length() > 4) {
                return Optional.of(token.substring(4));
            }
        }
        return Optional.empty();
    }

    public Double heapMaxBytes() {
        return positive(raw.path("heap").path("max"));
    }

    /** Null when the pod limit is missing or not positive. */
    public Double podMemoryLimitBytes() {
        return positive(raw.path("container").path("memory_limit"));
    }

    public String youngCollector() {
        return raw.path("gc").path("young").asText("");
    }

    public String oldCollector() {
        return raw.path("gc").path("old").asText("");
    }

    private static JsonNode parse(String settings) {
        try {
            if (settings == null || settings.isBlank()) {
                return JSON.createObjectNode();
            }
            return JSON.readTree(settings);
        } catch (Exception error) {
            return JSON.createObjectNode();
        }
    }

    private static Double positive(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isNumber()) {
            return null;
        }
        double value = node.asDouble();
        if (value <= 0d || Double.isNaN(value)) {
            return null;
        }
        return value;
    }
}

package io.jafra.analyzer.presets;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.jafra.analyzer.signals.ContainerMath;

import jdk.jfr.Configuration;

/** The three shipped JFC presets, and how a chunk's active settings map onto one. */
public final class Presets {
    public static final List<String> IDS = List.of("aggressive", "medium", "low");

    private Presets() {}

    public static List<Map<String, Object>> catalog() {
        List<Map<String, Object>> presets = new ArrayList<>();
        for (String id : IDS) {
            Configuration configuration = load(id);
            Map<String, String> settings = configuration.getSettings();
            Map<String, Object> preset = new LinkedHashMap<>();
            preset.put("id", id);
            preset.put("label", configuration.getLabel());
            preset.put("description", configuration.getDescription());
            preset.put("execution_sample_period", settings.get("jdk.ExecutionSample#period"));
            preset.put("socket_read_threshold", settings.get("jdk.SocketRead#threshold"));
            preset.put("container_cpu_throttling_period", settings.get("jdk.ContainerCPUThrottling#period"));
            Map<String, String> thresholds = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : settings.entrySet()) {
                int mark = entry.getKey().indexOf("#threshold");
                if (mark > 0) {
                    thresholds.put(entry.getKey().substring(0, mark), entry.getValue());
                }
            }
            preset.put("thresholds", thresholds);
            presets.add(preset);
        }
        return presets;
    }

    public static String match(String executionPeriod, String socketThreshold, String throttlePeriod, boolean profiler) {
        for (Map<String, Object> preset : catalog()) {
            if (same(executionPeriod, String.valueOf(preset.get("execution_sample_period")))
                    && same(socketThreshold, String.valueOf(preset.get("socket_read_threshold")))
                    && same(throttlePeriod, String.valueOf(preset.get("container_cpu_throttling_period")))) {
                return String.valueOf(preset.get("id"));
            }
        }
        if (same(executionPeriod, "20 ms") && same(socketThreshold, "20 ms")) {
            return "default";
        }
        if (profiler) {
            return "profile";
        }
        return "unknown";
    }

    public static Configuration load(String id) {
        String name = "/jfc/jafra-" + id + ".jfc";
        try (InputStream in = Presets.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing " + name);
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return Configuration.create(reader);
            }
        } catch (IOException | java.text.ParseException error) {
            throw new IllegalStateException("could not parse " + name, error);
        }
    }

    private static boolean same(String left, String right) {
        Double a = ContainerMath.millis(left);
        Double b = ContainerMath.millis(right);
        if (a == null || b == null) {
            return false;
        }
        return Math.abs(a - b) < 0.001;
    }
}

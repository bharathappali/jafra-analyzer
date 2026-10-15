package io.jafra.analyzer.presets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jdk.jfr.Configuration;

class PresetTest {
    @Test
    void eachPresetParsesWithTheJdkReader() throws Exception {
        for (String id : Presets.IDS) {
            Configuration configuration = Configuration.create(Path.of("src/main/resources/jfc/jafra-" + id + ".jfc"));
            assertTrue(configuration.getSettings().containsKey("jdk.ExecutionSample#period"));
            assertTrue(configuration.getSettings().containsKey("jdk.SocketRead#threshold"));
            assertTrue(configuration.getSettings().containsKey("jdk.ContainerCPUThrottling#period"));
        }
        List<Map<String, Object>> catalog = Presets.catalog();
        assertEquals(List.of("aggressive", "medium", "low"), catalog.stream().map(row -> row.get("id")).toList());
        assertEquals("5 ms", catalog.get(1).get("socket_read_threshold"));
    }

    @Test
    void theThreeSettingsPickThePreset() {
        assertEquals("aggressive", Presets.match("10 ms", "1 ms", "1 s", false));
        assertEquals("medium", Presets.match("20ms", "5 ms", "5 s", true));
        assertEquals("low", Presets.match("50 ms", "20 ms", "15 s", false));
        assertEquals("default", Presets.match("20 ms", "20 ms", "30 s", true));
        assertEquals("profile", Presets.match("10 s", "0 ms", "1 s", true));
        assertEquals("unknown", Presets.match(null, null, null, false));
    }
}

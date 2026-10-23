package io.jafra.analyzer.v2.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.jafra.analyzer.v2.store.V2Store.JvmRow;

class JvmInfoContractTest {
    @Test
    void heapDropsHelperKeys() {
        String settings = """
                {"heap":{"max":1073741824,"xmx":"1g","max_ram_percentage":"25.0"},"host":{"os":"Ubuntu 24.04.1 LTS"},"gc":{"parallel_threads":4}}
                """;
        JvmRow jvm = new JvmRow(1, "c", "ns", "pod", "uid", "app", 1L, 21, "21", "low", 0L, 1L, 1, settings);
        JsonNode body = ToolRegistry.jvmInfo(jvm);
        assertFalse(body.get("heap").has("xmx"));
        assertFalse(body.get("heap").has("max_ram_percentage"));
        assertEquals(1073741824L, body.get("heap").get("max").asLong());
        assertEquals("Ubuntu 24.04.1 LTS", body.get("host").get("os").asText());
        assertEquals(4, body.get("gc").get("parallel_threads").asInt());
    }
}

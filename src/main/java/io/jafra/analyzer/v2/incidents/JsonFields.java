package io.jafra.analyzer.v2.incidents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class JsonFields {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonFields() {}

    static JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (Exception error) {
            return null;
        }
    }
}

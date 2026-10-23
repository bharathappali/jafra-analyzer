package io.jafra.analyzer.v2.rollup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A later chunk must not wipe JVM settings it did not record. */
public final class SettingsMerge {
    private static final ObjectMapper JSON = new ObjectMapper();

    private SettingsMerge() {}

    public static String merge(String priorJson, String nextJson) {
        JsonNode prior = object(priorJson);
        JsonNode next = object(nextJson);
        JsonNode merged = merge(prior, next);
        try {
            return JSON.writeValueAsString(merged == null || merged.isNull() ? JSON.createObjectNode() : merged);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            return priorJson == null || priorJson.isBlank() ? "{}" : priorJson;
        }
    }

    private static JsonNode merge(JsonNode prior, JsonNode next) {
        if (absent(next)) {
            return prior == null || prior.isMissingNode() ? next : prior;
        }
        if (prior == null || prior.isNull() || prior.isMissingNode() || absent(prior)) {
            return next;
        }
        if (prior.isObject() && next.isObject()) {
            ObjectNode out = ((ObjectNode) prior).deepCopy();
            next.fields().forEachRemaining(entry -> out.set(entry.getKey(), merge(prior.get(entry.getKey()), entry.getValue())));
            return out;
        }
        return next;
    }

    private static boolean absent(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return true;
        }
        if (node.isTextual()) {
            String text = node.asText();
            return text.isBlank() || text.startsWith("1970-01-01T00:00:00");
        }
        if (node.isObject() || node.isArray()) {
            return node.isEmpty();
        }
        return false;
    }

    private static JsonNode object(String json) {
        if (json == null || json.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            JsonNode tree = JSON.readTree(json);
            return tree instanceof ObjectNode object ? object : JSON.createObjectNode();
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            return JSON.createObjectNode();
        }
    }
}

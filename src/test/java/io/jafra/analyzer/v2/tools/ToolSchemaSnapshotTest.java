package io.jafra.analyzer.v2.tools;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.jafra.analyzer.v2.catalogue.Catalogue;
import io.jafra.analyzer.v2.catalogue.EventSpec;
import io.jafra.analyzer.v2.catalogue.FrameKindSpec;
import io.jafra.analyzer.v2.catalogue.MetricSpec;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ToolSchemaSnapshotTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void publishedSchemaMatchesTheSnapshotAndTheCatalogue() throws Exception {
        Catalogue catalogue = new Catalogue();
        ObjectNode built = new ToolRegistry(null, null, null, null, null, null, catalogue).schemas();
        Path snapshot = Path.of("src/test/resources/v2/tools-schema.json");
        if (!Files.exists(snapshot)) {
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(built) + "\n");
        }

        String body = given().get("/v2/tools").then().statusCode(200).extract().asString();
        JsonNode http = JSON.readTree(body);
        assertEquals(JSON.readTree(snapshot.toFile()), http);
        JsonNode byName = index(http.get("tools"));
        assertEquals(List.of(), names(byName.get("get_overview").get("parameters").get("required")));
        assertEquals(List.of("metric"), names(byName.get("query_metric").get("parameters").get("required")));
        assertEquals(List.of("metric"), names(byName.get("get_trend").get("parameters").get("required")));
        assertEquals(List.of("type"), names(byName.get("list_events").get("parameters").get("required")));
        assertEquals(List.of("kind"), names(byName.get("top_frames").get("parameters").get("required")));
        assertEquals(List.of("id"), names(byName.get("get_incident").get("parameters").get("required")));
        assertEquals(catalogue.groups(), names(byName.get("list_incidents").get("parameters").get("properties").get("group").get("enum")));
        assertEquals(
                catalogue.trendMetrics().stream().map(MetricSpec::name).toList(),
                names(byName.get("get_trend").get("parameters").get("properties").get("metric").get("enum")));
        assertEquals(
                catalogue.events().stream().map(EventSpec::type).toList(),
                names(byName.get("list_events").get("parameters").get("properties").get("type").get("enum")));
        assertEquals(
                catalogue.frameKinds().stream().map(FrameKindSpec::kind).toList(),
                names(byName.get("top_frames").get("parameters").get("properties").get("kind").get("enum")));
        assertFalse(byName.get("get_overview").get("parameters").get("additionalProperties").asBoolean());
        assertEquals("duration", byName.get("list_events").get("parameters").get("properties").get("sort").get("default").asText());
        assertEquals("avg", byName.get("query_metric").get("parameters").get("properties").get("stat").get("default").asText());
        assertEquals(10, byName.get("query_metric").get("parameters").get("properties").get("top").get("default").asInt());
        assertEquals(1, byName.get("query_metric").get("parameters").get("properties").get("top").get("minimum").asInt());
        assertEquals(15, byName.get("top_frames").get("parameters").get("properties").get("top").get("maximum").asInt());
        assertEquals("object", http.get("context").get("properties").get("range").get("type").asText());
    }

    private static JsonNode index(JsonNode tools) {
        var found = JSON.createObjectNode();
        for (JsonNode tool : tools) {
            found.set(tool.get("name").asText(), tool);
        }
        return found;
    }

    private static List<String> names(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}

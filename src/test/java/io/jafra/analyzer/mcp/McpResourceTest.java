package io.jafra.analyzer.mcp;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class McpResourceTest {
    @Test
    void everyToolPublishesASchemaAndWorkloadIsRequired() {
        List<Map<String, Object>> tools = given().contentType(ContentType.JSON)
                .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .post("/mcp")
                .then()
                .statusCode(200)
                .extract()
                .path("result.tools");
        assertEquals(23, tools.size());
        for (Map<String, Object> tool : tools) {
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) tool.get("inputSchema");
            assertEquals("object", schema.get("type"));
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) schema.get("required");
            if ("list_workloads".equals(tool.get("name"))) {
                assertTrue(required.isEmpty());
            } else {
                assertTrue(required.contains("workload"));
            }
        }
    }

    @Test
    void aToolWithoutWorkloadIsRejectedAndListWorkloadsStaysSmall() {
        given().contentType(ContentType.JSON)
                .body(Map.of(
                        "jsonrpc", "2.0",
                        "id", 2,
                        "method", "tools/call",
                        "params", Map.of("name", "top", "arguments", Map.of("kind", "cpu"))))
                .post("/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", equalTo(true));
        String text = given().contentType(ContentType.JSON)
                .body(Map.of(
                        "jsonrpc", "2.0",
                        "id", 3,
                        "method", "tools/call",
                        "params", Map.of("name", "list_workloads", "arguments", Map.of())))
                .post("/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", equalTo(false))
                .extract()
                .path("result.content[0].text");
        assertTrue(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= McpCatalog.SERIES_BYTES);
        given().contentType(ContentType.JSON)
                .body(Map.of("jsonrpc", "2.0", "id", 4, "method", "nope"))
                .post("/mcp")
                .then()
                .statusCode(200)
                .body("error.code", equalTo(-32601));
    }
}

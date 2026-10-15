package io.jafra.analyzer.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.jafra.analyzer.api.v2.V2Database;
import io.jafra.analyzer.api.v2.V2ReadService;

/** Streamable HTTP MCP at /mcp. Tools are the /v2 reads with compact output. */
@Path("/mcp")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class McpResource {
    private final V2ReadService reads;
    private final ObjectMapper json = new ObjectMapper();

    @Inject
    public McpResource(V2Database database) {
        this.reads = new V2ReadService(database.connection(), database.root());
    }

    @POST
    public Response post(Map<String, Object> body) {
        if (body == null || body.get("method") == null) {
            return error(null, -32600, "method is required");
        }
        Object id = body.get("id");
        String method = String.valueOf(body.get("method"));
        if (method.startsWith("notifications/")) {
            return Response.accepted().build();
        }
        try {
            Object result = switch (method) {
                case "initialize" -> initialize();
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", McpCatalog.listed());
                case "tools/call" -> call(map(body.get("params")));
                default -> null;
            };
            if (result == null) {
                return error(id, -32601, "unknown method " + method);
            }
            return Response.ok(rpc(id, result)).build();
        } catch (IllegalArgumentException error) {
            return error(id, -32602, error.getMessage());
        } catch (Exception error) {
            return error(id, -32603, error.getMessage());
        }
    }

    private Map<String, Object> call(Map<String, Object> params) throws Exception {
        String name = params.get("name") == null ? "" : String.valueOf(params.get("name"));
        boolean known = McpCatalog.tools().stream().anyMatch(tool -> tool.name().equals(name));
        if (!known) {
            throw new IllegalArgumentException("unknown tool " + name);
        }
        Map<String, Object> arguments = map(params.get("arguments"));
        boolean failed = false;
        Map<String, Object> body;
        try {
            body = new McpTools(reads).call(name, arguments);
            body = McpTools.fit(body, McpCatalog.budget(name), json);
        } catch (IllegalArgumentException error) {
            failed = true;
            body = new LinkedHashMap<>();
            body.put("status", "invalid_request");
            body.put("reason", error.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", json.writeValueAsString(body))));
        result.put("isError", failed);
        return result;
    }

    private static Map<String, Object> initialize() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "2025-03-26");
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        result.put("serverInfo", Map.of("name", "jafra-analyzer", "version", "0.0.2"));
        return result;
    }

    private static Map<String, Object> rpc(Object id, Object result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("result", result);
        return body;
    }

    private static Response error(Object id, int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("error", Map.of("code", code, "message", message == null ? "error" : message));
        return Response.ok(body).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, item) -> out.put(String.valueOf(key), item));
        return out;
    }
}

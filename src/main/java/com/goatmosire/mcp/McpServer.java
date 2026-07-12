package com.goatmosire.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.goatmosire.service.MapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

/**
 * MCP (Model Context Protocol) JSON-RPC 2.0 server over stdio.
 *
 * <p>Implements the minimum MCP spec: initialize, tools/list, tools/call.
 * No external MCP library needed — pure JDK + Jackson.
 *
 * <p>Registered tools are prefixed "goatmosire_" for Hermes auto-discovery.
 */
public class McpServer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MapService mapService;
    private final McpToolRegistry registry;
    private volatile boolean running = true;

    public McpServer(MapService mapService) {
        this.mapService = mapService;
        this.registry = new McpToolRegistry(mapService);
    }

    @Override
    public void run() {
        start();
    }

    public void start() {
        log.info("MCP server starting on stdio...");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out), true)) {

            String line;
            while (running && (line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode req = MAPPER.readTree(line);
                    String method = req.has("method") ? req.get("method").asText() : "";
                    String id = req.has("id") && !req.get("id").isNull()
                        ? req.get("id").toString() : null;

                    switch (method) {
                        case "initialize"        -> out.println(jsonRpc(id, handleInitialize(req)));
                        case "notifications/initialized" -> { /* no-op */ }
                        case "tools/list"        -> out.println(jsonRpc(id, handleToolsList()));
                        case "tools/call"        -> out.println(jsonRpc(id, handleToolCall(req)));
                        default -> out.println(jsonRpcError(id, -32601, "Method not found: " + method));
                    }
                } catch (Exception e) {
                    log.error("MCP error", e);
                    out.println(jsonRpcError(null, -32700, "Parse error: " + e.getMessage()));
                }
            }
        } catch (IOException e) {
            log.error("MCP I/O error", e);
        }
        log.info("MCP server stopped");
    }

    public void stop() {
        running = false;
    }

    // ── Handlers ──────────────────────────────────────────

    private JsonNode handleInitialize(JsonNode req) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", "2024-11-05");

        ObjectNode caps = MAPPER.createObjectNode();
        caps.set("tools", MAPPER.createObjectNode());
        result.set("capabilities", caps);

        ObjectNode info = MAPPER.createObjectNode();
        info.put("name", "GoatMosire");
        info.put("version", "0.1.0");
        result.set("serverInfo", info);

        return result;
    }

    private JsonNode handleToolsList() {
        ArrayNode tools = MAPPER.createArrayNode();
        for (McpToolRegistry.ToolDef tool : registry.all()) {
            ObjectNode t = MAPPER.createObjectNode();
            t.put("name", tool.name());
            t.put("description", tool.description());
            try {
                t.set("inputSchema", MAPPER.readTree(tool.schema()));
            } catch (Exception e) {
                log.warn("Invalid schema for tool {}", tool.name(), e);
            }
            tools.add(t);
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    private JsonNode handleToolCall(JsonNode req) {
        JsonNode params = req.get("params");
        String toolName = params.has("name") ? params.get("name").asText() : "";
        JsonNode args = params.has("arguments") ? params.get("arguments")
            : MAPPER.createObjectNode();

        try {
            String result = registry.execute(toolName, args);
            ArrayNode content = MAPPER.createArrayNode();
            ObjectNode text = MAPPER.createObjectNode();
            text.put("type", "text");
            text.put("text", result);
            content.add(text);
            ObjectNode out = MAPPER.createObjectNode();
            out.set("content", content);
            return out;
        } catch (IllegalArgumentException e) {
            return errorResult(-32602, "Unknown tool: " + toolName);
        } catch (Exception e) {
            log.error("Tool error: {}", toolName, e);
            return errorResult(-32000, "Tool error: " + e.getMessage());
        }
    }

    // ── JSON-RPC helpers ──────────────────────────────────

    private static String jsonRpc(String id, JsonNode result) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("jsonrpc", "2.0");
        if (id != null) {
            try { r.set("id", MAPPER.readTree(id)); }
            catch (Exception e) { r.put("id", id); }
        }
        r.set("result", result);
        return r.toString();
    }

    private static String jsonRpcError(String id, int code, String message) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("jsonrpc", "2.0");
        if (id != null) {
            try { r.set("id", MAPPER.readTree(id)); }
            catch (Exception e) { r.put("id", id); }
        }
        ObjectNode err = MAPPER.createObjectNode();
        err.put("code", code);
        err.put("message", message);
        r.set("error", err);
        return r.toString();
    }

    private static JsonNode errorResult(int code, String message) {
        ObjectNode r = MAPPER.createObjectNode();
        ObjectNode err = MAPPER.createObjectNode();
        err.put("code", code);
        err.put("message", message);
        r.set("error", err);
        return r;
    }
}

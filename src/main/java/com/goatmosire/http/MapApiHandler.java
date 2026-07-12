package com.goatmosire.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsim.map.*;
import com.goatmosire.service.MapService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * REST API handler for /api/map/* endpoints.
 *
 * <pre>
 *   GET  /api/map/{worldId}?node={nodeId}   → resolve map
 *   GET  /api/map/{worldId}/history?node={n} → history chain
 *   PUT  /api/map/{worldId}?node={nodeId}    → save diff (or full for root)
 *   POST /api/map/{worldId}?node={nodeId}    → create new full map
 *   GET  /api/map                           → list worlds with maps
 * </pre>
 */
public class MapApiHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(MapApiHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MapService mapService;

    public MapApiHandler(MapService mapService) {
        this.mapService = mapService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Content-Type", "application/json");

        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        try {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query);

            // Strip /api/map prefix
            String sub = path.substring("/api/map".length());

            if (sub.isEmpty() || sub.equals("/")) {
                handleList(exchange);
            } else if (sub.endsWith("/history")) {
                handleHistory(exchange, sub, params);
            } else if (sub.endsWith("/nodes")) {
                handleNodes(exchange, sub);
            } else {
                String worldId = sub.startsWith("/") ? sub.substring(1) : sub;
                if (worldId.contains("/")) worldId = worldId.substring(0, worldId.indexOf("/"));
                String method = exchange.getRequestMethod();
                switch (method) {
                    case "GET" -> handleGet(exchange, worldId, params);
                    case "POST" -> handleCreate(exchange, worldId, params);
                    case "PUT" -> handleSave(exchange, worldId, params);
                    default -> sendError(exchange, 405, "Method not allowed: " + method);
                }
            }
        } catch (Exception e) {
            log.error("API error", e);
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ── GET /api/map/{worldId} ────────────────────────────

    private void handleGet(HttpExchange exchange, String worldId, Map<String, String> params) throws IOException {
        String nodeId = params.getOrDefault("node", readActiveNodeId(worldId));
        if (nodeId == null) {
            sendError(exchange, 404, "World not found or no active node: " + worldId);
            return;
        }
        MapData map = mapService.resolve(worldId, nodeId);
        sendJson(exchange, 200, map);
    }

    // ── GET /api/map/{worldId}/history ────────────────────

    private void handleHistory(HttpExchange exchange, String sub, Map<String, String> params) throws IOException {
        String worldId = sub.substring(1, sub.indexOf("/history"));
        String nodeId = params.getOrDefault("node", readActiveNodeId(worldId));
        if (nodeId == null) {
            sendError(exchange, 404, "World not found: " + worldId);
            return;
        }
        List<MapResolver.HistoryEntry> history = mapService.history(worldId, nodeId);
        sendJson(exchange, 200, history);
    }

    // ── GET /api/map (list worlds) ────────────────────────

    private void handleList(HttpExchange exchange) throws IOException {
        List<String> worlds = mapService.listWorldsWithMaps();
        sendJson(exchange, 200, Map.of("worlds", worlds));
    }

    // ── GET /api/map/{worldId}/nodes ────────────────────

    private void handleNodes(HttpExchange exchange, String sub) throws IOException {
        String worldId = sub.substring(1, sub.indexOf("/nodes"));
        List<Map<String, Object>> nodes = mapService.listNodes(worldId);
        sendJson(exchange, 200, Map.of("worldId", worldId, "nodes", nodes));
    }

    // ── POST /api/map/{worldId} (create full map) ─────────

    private void handleCreate(HttpExchange exchange, String worldId, Map<String, String> params) throws IOException {
        if (!mapService.worldExists(worldId)) {
            sendError(exchange, 404, "World not found: " + worldId);
            return;
        }
        String nodeId = params.getOrDefault("node", "n0000");
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        MapData data = MAPPER.readValue(body, MapData.class);
        mapService.saveFull(worldId, nodeId, data);
        log.info("Created map for world={} node={}", worldId, nodeId);
        sendJson(exchange, 200, Map.of("ok", true, "worldId", worldId, "nodeId", nodeId));
    }

    // ── PUT /api/map/{worldId} (save diff or full) ────────

    private void handleSave(HttpExchange exchange, String worldId, Map<String, String> params) throws IOException {
        String nodeId = params.getOrDefault("node", readActiveNodeId(worldId));
        if (nodeId == null) {
            sendError(exchange, 404, "No active node for world: " + worldId);
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        try {
            var root = MAPPER.readTree(body);
            if (root.has("parentNodeId")) {
                // Explicit diff from client
                MapDiff diff = MAPPER.readValue(body, MapDiff.class);
                mapService.saveDiff(worldId, nodeId, diff);
                log.info("Saved diff for world={} node={} ({} hex changes)", worldId, nodeId, diff.changed().size());
            } else {
                MapData data = MAPPER.readValue(body, MapData.class);
                if (isRootNode(worldId, nodeId)) {
                    // Root node: save full map
                    mapService.saveFull(worldId, nodeId, data);
                    log.info("Saved full map for root node world={} node={}", worldId, nodeId);
                } else {
                    // Child node: auto-compute diff vs parent's resolved map
                    String parentId = readParentNodeId(worldId, nodeId);
                    MapData parentMap = parentId != null ? mapService.resolve(worldId, parentId) : MapData.empty();
                    MapDiff diff = MapDiff.compute(parentId, parentMap, data);
                    mapService.saveDiff(worldId, nodeId, diff);
                    log.info("Auto-computed diff for world={} node={} ({} changed, {} removed)",
                        worldId, nodeId, diff.changed().size(), diff.removed().size());
                }
            }
            sendJson(exchange, 200, Map.of("ok", true, "worldId", worldId, "nodeId", nodeId));
        } catch (Exception e) {
            sendError(exchange, 400, "Invalid map data: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────

    private void sendJson(HttpExchange exchange, int status, Object data) throws IOException {
        String json = MAPPER.writeValueAsString(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", message));
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) params.put(kv[0], java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return params;
    }

    private String readActiveNodeId(String worldId) {
        try {
            var file = java.nio.file.Path.of(
                System.getProperty("user.home"), "GSimulator/worlds", worldId, "active.json");
            if (!java.nio.file.Files.exists(file)) return null;
            var node = MAPPER.readTree(file.toFile());
            return node.has("nodeId") ? node.get("nodeId").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isRootNode(String worldId, String nodeId) {
        return readParentNodeId(worldId, nodeId) == null;
    }

    private String readParentNodeId(String worldId, String nodeId) {
        try {
            var file = java.nio.file.Path.of(
                System.getProperty("user.home"), "GSimulator/worlds", worldId, "nodes", nodeId + ".json");
            if (!java.nio.file.Files.exists(file)) return null;
            var node = MAPPER.readTree(file.toFile());
            if (node.has("parentId") && !node.get("parentId").isNull()) {
                String pid = node.get("parentId").asText();
                return pid.isBlank() ? null : pid;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}

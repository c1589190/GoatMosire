package com.goatmosire.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsim.map.*;
import com.goatmosire.service.MapService;
import com.goatmosire.service.MapGenerator;
import com.goatmosire.service.ContinentContour;
import com.goatmosire.service.ContourLayer;
import com.goatmosire.service.ContourQueryEngine;
import com.goatmosire.service.TerrainCanvas;
import com.goatmosire.service.TerrainGeometry;
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
            } else if (sub.endsWith("/river-path")) {
                handleRiverPath(exchange, sub, params);
            } else if (sub.endsWith("/generate")) {
                handleGenerate(exchange, sub, params);
            } else if (sub.contains("/contour")) {
                handleContour(exchange, sub, params);
            } else if (sub.endsWith("/blocks")) {
                handleBlocks(exchange, sub, params);
            } else if (sub.endsWith("/query-terrain")) {
                handleQueryTerrain(exchange, sub, params);
            } else {
                String worldId = sub.startsWith("/") ? sub.substring(1) : sub;
                if (worldId.contains("/")) worldId = worldId.substring(0, worldId.indexOf("/"));
                String method = exchange.getRequestMethod();
                switch (method) {
                    case "GET" -> handleGet(exchange, worldId, params);
                    case "POST" -> {
                        if ("true".equals(params.get("materialize"))) {
                            handleGet(exchange, worldId, params);
                        } else {
                            handleCreate(exchange, worldId, params);
                        }
                    }
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

        // Materialize from contour if requested
        if ("true".equals(params.get("materialize"))) {
            ContinentContour contour = mapService.loadContour(worldId);
            if (contour == null) {
                sendError(exchange, 404, "No contour found for world: " + worldId);
                return;
            }
            ContourQueryEngine engine = new ContourQueryEngine(contour);
            MapData map = engine.materialize(-contour.radius, contour.radius, -contour.radius, contour.radius);
            mapService.saveFull(worldId, "n0000", map);
            sendJson(exchange, 200, Map.of("ok", true, "hexCount", map.hexes().size()));
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

    // ── GET /api/map/{worldId}/river-path?q=&r=&node= ────

    private void handleRiverPath(HttpExchange exchange, String sub, Map<String, String> params) throws IOException {
        String worldId = sub.substring(1, sub.indexOf("/river-path"));
        String nodeId = params.get("node");
        int q = Integer.parseInt(params.getOrDefault("q", "0"));
        int r = Integer.parseInt(params.getOrDefault("r", "0"));
        List<String> path = mapService.findRiverPath(worldId, nodeId, q, r);
        sendJson(exchange, 200, Map.of("source", Map.of("q", q, "r", r), "path", path, "length", path.size()));
    }

    // ── POST /api/map/{worldId}/generate ─────────────────
    // NOTE: This uses the @Deprecated MapGenerator for backward compat.
    // New worlds should start with empty canvas and add TerrainBlocks via editor.

    @SuppressWarnings("deprecation")
    private void handleGenerate(HttpExchange exchange, String sub, Map<String, String> params) throws IOException {
        String worldId = sub.substring(1, sub.indexOf("/generate"));
        long seed = Long.parseLong(params.getOrDefault("seed", String.valueOf(System.currentTimeMillis())));
        int radius = Integer.parseInt(params.getOrDefault("radius", "80"));
        int mainCount = Integer.parseInt(params.getOrDefault("ridges", "4"));
        int fragmentCount = Integer.parseInt(params.getOrDefault("fragments", "8"));
        double landRatio = Double.parseDouble(params.getOrDefault("land", "0.35"));
        double coastRoughness = Double.parseDouble(params.getOrDefault("roughness", "0.6"));

        // Generate continent (deprecated path — use TerrainCanvas.addBlock for new worlds)
        var gen = new MapGenerator(seed, radius);
        gen.placeRidges(mainCount, fragmentCount);
        ContinentContour contour = gen.generateContour(landRatio);
        mapService.saveContour(worldId, contour);

        // Materialize full map
        MapData map = MapGenerator.generate(worldId, seed, radius, mainCount, fragmentCount, landRatio, coastRoughness);
        mapService.saveFull(worldId, "n0000", map);
        mapService.evictCanvas(worldId);

        sendJson(exchange, 200, Map.of(
            "ok", true, "worldId", worldId, "nodeId", "n0000",
            "seed", seed, "hexCount", map.hexes().size(),
            "landHexes", map.hexes().values().stream().filter(c -> !"water".equals(c.terrain())).count()
        ));
    }

    // ── GET/PUT /api/map/{worldId}/contour[/editor-layers] ─

    private void handleContour(HttpExchange exchange, String sub, Map<String, String> params) throws IOException {
        String worldId = sub.substring(1, sub.indexOf("/contour"));

        if (sub.endsWith("/editor-layers") && "PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
            // PUT editor layers
            var body = MAPPER.readTree(exchange.getRequestBody());
            ContinentContour contour = mapService.loadContour(worldId);
            if (contour == null) {
                sendJson(exchange, 404, Map.of("error", "no contour found"));
                return;
            }
            List<ContourLayer> layers = new ArrayList<>();
            for (var node : body) {
                String terrain = node.get("terrain").asText();
                List<ContinentContour.Pt> pts = new ArrayList<>();
                for (var pt : node.get("boundary")) {
                    pts.add(new ContinentContour.Pt(pt.get("x").asDouble(), pt.get("y").asDouble()));
                }
                String seed = node.has("seedKey") ? node.get("seedKey").asText() : "";
                layers.add(new ContourLayer(terrain, pts, seed));
            }
            contour.editorLayers = layers;
            mapService.saveContour(worldId, contour);
            sendJson(exchange, 200, Map.of("ok", true, "layers", layers.size()));
        } else {
            // GET contour
            ContinentContour contour = mapService.loadContour(worldId);
            if (contour == null) {
                sendJson(exchange, 404, Map.of("error", "no contour found"));
                return;
            }
            sendJson(exchange, 200, contour);
        }
    }

    // ── GET/PUT/DELETE /api/map/{worldId}/blocks ─────────

    private void handleBlocks(HttpExchange exchange, String sub, Map<String, String> params) throws IOException {
        String worldId = sub.substring(1, sub.indexOf("/blocks"));
        String method = exchange.getRequestMethod();

        switch (method) {
            case "GET" -> {
                // List blocks
                TerrainCanvas canvas = mapService.getCanvas(worldId);
                List<MapData.TerrainBlock> blocks = canvas.getBlocks();
                sendJson(exchange, 200, Map.of("worldId", worldId, "blocks", blocks, "count", blocks.size()));
            }
            case "POST" -> {
                // Add a block
                var body = MAPPER.readTree(exchange.getRequestBody());
                String terrain = body.get("terrain").asText();
                List<MapData.Pt> bnd = new ArrayList<>();
                for (var pt : body.get("boundary")) {
                    bnd.add(new MapData.Pt(pt.get("x").asDouble(), pt.get("y").asDouble()));
                }
                String seed = body.has("seedKey") ? body.get("seedKey").asText() : "";
                String blockId = mapService.addBlock(worldId, terrain, bnd, seed);
                if (blockId != null) {
                    sendJson(exchange, 200, Map.of("ok", true, "blockId", blockId, "terrain", terrain));
                } else {
                    sendJson(exchange, 200, Map.of("ok", false, "reason", "empty after overlap processing"));
                }
            }
            case "DELETE" -> {
                String blockId = params.get("id");
                if (blockId == null) {
                    sendError(exchange, 400, "Missing 'id' parameter");
                    return;
                }
                boolean ok = mapService.removeBlock(worldId, blockId);
                sendJson(exchange, 200, Map.of("ok", ok));
            }
            default -> sendError(exchange, 405, "Method not allowed: " + method);
        }
    }

    // ── GET /api/map/{worldId}/query-terrain?q=&r= ────────

    private void handleQueryTerrain(HttpExchange exchange, String sub, Map<String, String> params) throws IOException {
        String worldId = sub.substring(1, sub.indexOf("/query-terrain"));
        int q = Integer.parseInt(params.getOrDefault("q", "0"));
        int r = Integer.parseInt(params.getOrDefault("r", "0"));
        String terrain = mapService.queryTerrainBlock(worldId, q, r);
        sendJson(exchange, 200, Map.of("q", q, "r", r, "terrain", terrain != null ? terrain : "empty"));
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

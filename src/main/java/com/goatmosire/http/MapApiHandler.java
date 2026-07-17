package com.goatmosire.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsim.map.*;
import com.goatmosire.service.MapService;
import com.goatmosire.service.MapGenerator;
import com.goatmosire.service.ContinentContour;
import com.goatmosire.service.ContourLayer;
import com.goatmosire.service.LassoProcessor;
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
            } else if (sub.endsWith("/rename-region")) {
                handleRenameRegion(exchange, sub, params);
            } else if (sub.endsWith("/latest-texts")) {
                handleLatestTexts(exchange, sub, params);
            } else if (sub.endsWith("/version")) {
                handleVersion(exchange, sub, params);
            } else if (sub.endsWith("/expand")) {
                handleExpand(exchange, sub, params);
            } else if (sub.endsWith("/compress")) {
                handleCompress(exchange, sub, params);
            } else if (sub.endsWith("/decompress")) {
                handleDecompress(exchange, sub, params);
            } else if (sub.endsWith("/decompress-at")) {
                handleDecompressAt(exchange, sub, params);
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
        // Inject per-node compressedRegions into response (now stored in _cr.json)
        com.fasterxml.jackson.databind.node.ObjectNode mapJson = MAPPER.valueToTree(map);
        List<MapData.CompressedRegion> crs = mapService.loadCr(worldId, nodeId);
        mapJson.set("compressedRegions", MAPPER.valueToTree(crs));
        sendJson(exchange, 200, mapJson);
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

    // ── GET /api/map/{worldId}/checkpoints?node={n} ─────

    // ── GET /api/map/{worldId}/latest-texts ─────────────

    private void handleLatestTexts(HttpExchange exchange, String sub, Map<String, String> params) throws IOException {
        String worldId = sub.substring(1, sub.indexOf("/latest-texts"));
        String nodeId = params.getOrDefault("node", readActiveNodeId(worldId));
        if (nodeId == null) {
            sendError(exchange, 404, "No active node for world: " + worldId);
            return;
        }
        try {
            var file = mapService.getWorldsDir().resolve(worldId).resolve("nodes").resolve(nodeId + ".json");
            if (!java.nio.file.Files.exists(file)) {
                sendError(exchange, 404, "Node not found: " + nodeId);
                return;
            }
            var node = MAPPER.readTree(file.toFile());
            var cps = node.get("checkpoints");
            List<Map<String, Object>> texts = new ArrayList<>();

            // Collect last 3 from narrative and factions, sorted by updatedAt
            for (String cpName : new String[]{"narrative", "factions"}) {
                if (cps != null && cps.has(cpName)) {
                    var elements = cps.get(cpName).get("elements");
                    if (elements != null && elements.isArray()) {
                        for (var el : elements) {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("checkpoint", cpName);
                            item.put("key", el.get("key").asText());
                            item.put("value", el.get("value").asText());
                            if (el.has("tags") && el.get("tags").isArray()) {
                                List<String> tags = new ArrayList<>();
                                for (var t : el.get("tags")) tags.add(t.asText());
                                item.put("tags", tags);
                            }
                            item.put("updatedAt", el.has("updatedAt") ? el.get("updatedAt").asText() : "");
                            texts.add(item);
                        }
                    }
                }
            }

            // Sort by updatedAt descending, take last 8
            texts.sort((a, b) -> String.valueOf(b.getOrDefault("updatedAt", ""))
                .compareTo(String.valueOf(a.getOrDefault("updatedAt", ""))));
            if (texts.size() > 8) texts = texts.subList(0, 8);

            sendJson(exchange, 200, Map.of("worldId", worldId, "nodeId", nodeId, "texts", texts));
        } catch (Exception e) {
            log.error("Failed to read latest texts", e);
            sendError(exchange, 500, "Failed: " + e.getMessage());
        }
    }

    // ── GET /api/map/{worldId}/version ──────────────────────

    private void handleVersion(HttpExchange exchange, String sub, Map<String, String> params) throws IOException {
        String worldId = sub.substring(1, sub.indexOf("/version"));
        String nodeId = params.getOrDefault("node", readActiveNodeId(worldId));
        if (nodeId == null) {
            sendError(exchange, 404, "No active node for world: " + worldId);
            return;
        }
        try {
            var mapFile = mapService.getWorldsDir().resolve(worldId).resolve("nodes").resolve(nodeId + "_map.json");
            long lastMod = java.nio.file.Files.exists(mapFile)
                ? java.nio.file.Files.getLastModifiedTime(mapFile).toMillis() : 0;
            sendJson(exchange, 200, Map.of("worldId", worldId, "nodeId", nodeId, "version", lastMod));
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ── POST /api/map/{worldId}/expand?direction=E&radius=N ─

    private void handleExpand(HttpExchange exchange, String sub, Map<String, String> params) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed, use POST");
            return;
        }
        String worldId = sub.substring(1, sub.indexOf("/expand"));
        String direction = params.getOrDefault("direction", "E").toUpperCase();
        int radius = Integer.parseInt(params.getOrDefault("radius", "0"));
        String nodeId = params.getOrDefault("node", readActiveNodeId(worldId));
        if (nodeId == null) {
            sendError(exchange, 404, "No active node for world: " + worldId);
            return;
        }
        try {
            var result = mapService.expand(worldId, nodeId, direction, radius);
            int status = Boolean.TRUE.equals(result.get("ok")) ? 200 : 400;
            sendJson(exchange, status, result);
        } catch (Exception e) {
            log.error("Expand failed", e);
            sendError(exchange, 500, "Expand failed: " + e.getMessage());
        }
    }

    // ── POST /api/map/{worldId}/compress?minSize=N ────────

    private void handleCompress(HttpExchange exchange, String sub, Map<String, String> params) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed, use POST");
            return;
        }
        String worldId = sub.substring(1, sub.indexOf("/compress"));
        int minSize = Integer.parseInt(params.getOrDefault("minSize", "100"));
        String nodeId = params.getOrDefault("node", readActiveNodeId(worldId));
        if (nodeId == null) {
            sendError(exchange, 404, "No active node for world: " + worldId);
            return;
        }
        try {
            var result = mapService.compress(worldId, nodeId, minSize);
            int status = Boolean.TRUE.equals(result.get("ok")) ? 200 : 400;
            sendJson(exchange, status, result);
        } catch (Exception e) {
            log.error("Compress failed", e);
            sendError(exchange, 500, "Compress failed: " + e.getMessage());
        }
    }

    // ── POST /api/map/{worldId}/decompress?region=xxx ─────

    private void handleDecompress(HttpExchange exchange, String sub, Map<String, String> params) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed, use POST");
            return;
        }
        String worldId = sub.substring(1, sub.indexOf("/decompress"));
        String regionId = params.get("region");
        if (regionId == null) {
            sendError(exchange, 400, "Missing 'region' parameter");
            return;
        }
        String nodeId = params.getOrDefault("node", readActiveNodeId(worldId));
        if (nodeId == null) {
            sendError(exchange, 404, "No active node for world: " + worldId);
            return;
        }
        try {
            var result = mapService.decompress(worldId, nodeId, regionId);
            int status = Boolean.TRUE.equals(result.get("ok")) ? 200 : 400;
            sendJson(exchange, status, result);
        } catch (Exception e) {
            log.error("Decompress failed", e);
            sendError(exchange, 500, "Decompress failed: " + e.getMessage());
        }
    }

    // ── POST /api/map/{worldId}/decompress-at?q=&r= ───────

    private void handleDecompressAt(HttpExchange exchange, String sub, Map<String, String> params) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed, use POST");
            return;
        }
        String worldId = sub.substring(1, sub.indexOf("/decompress-at"));
        int q = Integer.parseInt(params.getOrDefault("q", "0"));
        int r = Integer.parseInt(params.getOrDefault("r", "0"));
        String nodeId = params.getOrDefault("node", readActiveNodeId(worldId));
        if (nodeId == null) {
            sendError(exchange, 404, "No active node for world: " + worldId);
            return;
        }
        try {
            var result = mapService.decompressAt(worldId, nodeId, q, r);
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            log.error("Decompress-at failed", e);
            sendError(exchange, 500, "Decompress failed: " + e.getMessage());
        }
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
    // MapGenerator v5 — directional ridges + lowland-dominant terrain

    private void handleGenerate(HttpExchange exchange, String sub, Map<String, String> params) throws IOException {
        String worldId = sub.substring(1, sub.indexOf("/generate"));
        long seed = Long.parseLong(params.getOrDefault("seed", String.valueOf(System.currentTimeMillis())));
        int radius = Integer.parseInt(params.getOrDefault("radius", "80"));
        int mainCount = Integer.parseInt(params.getOrDefault("ridges", "2"));
        int fragmentCount = Integer.parseInt(params.getOrDefault("fragments", "5"));
        double landRatio = Double.parseDouble(params.getOrDefault("land", "0.55"));
        double coastRoughness = Double.parseDouble(params.getOrDefault("roughness", "0.6"));

        // Generate continent (deprecated path — use TerrainCanvas.addBlock for new worlds)
        var gen = new MapGenerator(seed, radius);
        gen.placeRidges(mainCount, fragmentCount);
        ContinentContour contour = gen.generateContour(landRatio);
        mapService.saveContour(worldId, contour);

        // Also materialize full map for editor rendering
        MapData map = MapGenerator.generate(worldId, seed, radius, mainCount, fragmentCount, landRatio, coastRoughness);
        mapService.saveFull(worldId, "n0000", map);

        // Populate terrain blocks from contour: create blocks for land areas
        populateTerrainBlocks(worldId, contour, radius);
        mapService.evictCanvas(worldId);

        try { mapService.syncToGSimNode(worldId, "n0000"); } catch (Exception ex) { log.warn("GSim node sync failed", ex); }
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
                var body = MAPPER.readTree(exchange.getRequestBody());
                String terrain = body.get("terrain").asText();
                String seed = body.has("seedKey") ? body.get("seedKey").asText() : "";

                // Accept lassoKeys (raw lasso hex keys) → backend fills
                if (body.has("lassoKeys")) {
                    List<String> rawKeys = new ArrayList<>();
                    for (var node : body.get("lassoKeys")) rawKeys.add(node.asText());
                    Set<String> hexSet = LassoProcessor.fill(rawKeys);
                    if (hexSet.isEmpty()) {
                        sendJson(exchange, 200, Map.of("ok", false, "reason", "lasso fill returned empty"));
                    } else {
                        String blockId = mapService.addBlockFromHexSet(worldId, terrain, hexSet, seed);
                        sendJson(exchange, 200, Map.of("ok", blockId != null, "blockId", blockId != null ? blockId : "", "terrain", terrain));
                    }
                } else if (body.has("hexKeys")) {
                    Set<String> hexSet = new HashSet<>();
                    for (var node : body.get("hexKeys")) hexSet.add(node.asText());
                    String blockId = mapService.addBlockFromHexSet(worldId, terrain, hexSet, seed);
                    sendJson(exchange, 200, Map.of("ok", blockId != null, "blockId", blockId != null ? blockId : "", "terrain", terrain));
                } else {
                    // Legacy: accept polygon boundary
                    List<MapData.Pt> bnd = new ArrayList<>();
                    for (var pt : body.get("boundary"))
                        bnd.add(new MapData.Pt(pt.get("x").asDouble(), pt.get("y").asDouble()));
                    String blockId = mapService.addBlock(worldId, terrain, bnd, seed);
                    sendJson(exchange, 200, Map.of("ok", blockId != null, "blockId", blockId != null ? blockId : "", "terrain", terrain,
                        "reason", blockId == null ? "empty after overlap processing" : ""));
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

    // ── POST /api/map/{worldId}/rename-region ─────────────

    private void handleRenameRegion(HttpExchange exchange, String sub, Map<String, String> params) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed, use POST");
            return;
        }
        String worldId = sub.substring(1, sub.indexOf("/rename-region"));
        String nodeId = readActiveNodeId(worldId);
        if (nodeId == null) {
            sendError(exchange, 404, "No active node for world: " + worldId);
            return;
        }
        var body = MAPPER.readTree(exchange.getRequestBody());
        String oldName = body.get("oldName").asText();
        String newName = body.get("newName").asText();
        if (oldName == null || newName == null) {
            sendError(exchange, 400, "Missing oldName or newName");
            return;
        }
        var result = mapService.renameRegion(worldId, nodeId, oldName, newName);
        int status = Boolean.TRUE.equals(result.get("ok")) ? 200 : 400;
        sendJson(exchange, status, result);
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
        byte[] raw = exchange.getRequestBody().readAllBytes();
        if (raw == null || raw.length == 0) {
            sendError(exchange, 400, "Request body is empty");
            return;
        }
        try {
            MapData data = MAPPER.readValue(raw, MapData.class);
            mapService.saveFull(worldId, nodeId, data);
            log.info("Created map for world={} node={}", worldId, nodeId);
            try { mapService.syncToGSimNode(worldId, nodeId); } catch (Exception ex) { log.warn("GSim node sync failed", ex); }
            sendJson(exchange, 200, Map.of("ok", true, "worldId", worldId, "nodeId", nodeId));
        } catch (Exception e) {
            sendError(exchange, 400, "Invalid map data: " + e.getMessage());
        }
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
                    mapService.saveFull(worldId, nodeId, data);
                    log.info("Saved full map for root node world={} node={} ({} CRs)", worldId, nodeId,
                        data.compressedRegions().size());
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
            try { mapService.syncToGSimNode(worldId, nodeId); } catch (Exception ex) { log.warn("GSim node sync failed", ex); }
            sendJson(exchange, 200, Map.of("ok", true, "worldId", worldId, "nodeId", nodeId));
        } catch (Exception e) {
            sendError(exchange, 400, "Invalid map data: " + e.getMessage());
        }
    }

    /** Create terrain blocks from contour ridge lines */
    private void populateTerrainBlocks(String worldId, ContinentContour contour, int radius) {
        TerrainCanvas canvas = mapService.getCanvas(worldId);

        for (ContinentContour.Ridge ridge : contour.ridges) {
            if (ridge.points.size() < 2) continue;

            // Build a thick polygon around the ridge line
            List<MapData.Pt> pts = new ArrayList<>();
            double width = radius * (ridge.weight > 0.8 ? 0.12 : 0.06);
            final double GRID = 30.0;

            for (int i = 0; i < ridge.points.size(); i++) {
                ContinentContour.Pt p = ridge.points.get(i);
                double angle = Math.atan2(
                    (i+1 < ridge.points.size() ? ridge.points.get(i+1).y : p.y + 1) - (i > 0 ? ridge.points.get(i-1).y : p.y - 1),
                    (i+1 < ridge.points.size() ? ridge.points.get(i+1).x : p.x + 1) - (i > 0 ? ridge.points.get(i-1).x : p.x - 1));
                double nx = Math.cos(angle + Math.PI/2) * width;
                double ny = Math.sin(angle + Math.PI/2) * width;
                // Convert axial → pixel (with GRID scaling for TerrainGeometry)
                double px = (p.x + p.y * 0.5) * GRID;
                double py = p.y * 0.8660254 * GRID;
                pts.add(new MapData.Pt(px + nx, py + ny));
            }
            for (int i = ridge.points.size() - 1; i >= 0; i--) {
                ContinentContour.Pt p = ridge.points.get(i);
                double angle = Math.atan2(
                    (i+1 < ridge.points.size() ? ridge.points.get(i+1).y : p.y + 1) - (i > 0 ? ridge.points.get(i-1).y : p.y - 1),
                    (i+1 < ridge.points.size() ? ridge.points.get(i+1).x : p.x + 1) - (i > 0 ? ridge.points.get(i-1).x : p.x - 1));
                double nx = Math.cos(angle - Math.PI/2) * width;
                double ny = Math.sin(angle - Math.PI/2) * width;
                // Convert axial → pixel (with GRID scaling)
                double px2 = (p.x + p.y * 0.5) * GRID;
                double py2 = p.y * 0.8660254 * GRID;
                pts.add(new MapData.Pt(px2 + nx, py2 + ny));
            }
            // Close
            if (!pts.isEmpty()) pts.add(new MapData.Pt(pts.get(0).x(), pts.get(0).y()));

            String terrain = ridge.weight > 0.8 ? "hills" : "plains";
            String seedKey = TerrainGeometry.hexKey(
                TerrainGeometry.pixelToHex(ridge.points.get(0).x, ridge.points.get(0).y)[0],
                TerrainGeometry.pixelToHex(ridge.points.get(0).x, ridge.points.get(0).y)[1]);
            canvas.addBlock(terrain, pts, seedKey);
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
            var file = mapService.getWorldsDir().resolve(worldId).resolve("active.json");
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
            var file = mapService.getWorldsDir().resolve(worldId).resolve("nodes").resolve(nodeId + ".json");
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

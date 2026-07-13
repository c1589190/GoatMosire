package com.goatmosire.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsim.map.*;
import com.goatmosire.service.MapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Registry of MCP tools exposed by GoatMosire.
 * All tools are prefixed "goatmosire_" for Hermes auto-discovery.
 */
public class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MapService mapService;
    private final Map<String, ToolDef> tools = new LinkedHashMap<>();

    public McpToolRegistry(MapService mapService) {
        this.mapService = mapService;
        registerAll();
    }

    public record ToolDef(String name, String description, String schema) {}

    public List<ToolDef> all() { return List.copyOf(tools.values()); }

    public String execute(String name, JsonNode args) throws Exception {
        ToolDef tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("Unknown tool: " + name);
        return switch (name) {
            case "goatmosire_get_hex"      -> handleGetHex(args);
            case "goatmosire_get_province"  -> handleGetProvince(args);
            case "goatmosire_get_neighbors" -> handleGetNeighbors(args);
            case "goatmosire_query_radius"  -> handleQueryRadius(args);
            case "goatmosire_get_cities"    -> handleGetCities(args);
            case "goatmosire_get_diff"      -> handleGetDiff(args);
            case "goatmosire_get_history"   -> handleGetHistory(args);
            case "goatmosire_list_worlds"   -> handleListWorlds(args);
            case "goatmosire_find_river_path" -> handleFindRiverPath(args);
            case "goatmosire_list_regions"  -> handleListRegions(args);
            case "goatmosire_get_distance"  -> handleGetDistance(args);
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
    }

    // ── Registration ──────────────────────────────────────

    private void registerAll() {
        register("goatmosire_get_hex",
            "Query a single hex cell by coordinates. Returns color, terrain type, symbol, and province ownership.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string","description":"GSim world ID"},
              "nodeId":{"type":"string","description":"Node ID (optional, defaults to active node)"},
              "q":{"type":"integer","description":"Axial q coordinate"},
              "r":{"type":"integer","description":"Axial r coordinate"}
            },"required":["worldId","q","r"]}""");

        register("goatmosire_get_province",
            "Query a province by name. Returns all hex cells belonging to it.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "name":{"type":"string","description":"Province name"}
            },"required":["worldId","name"]}""");

        register("goatmosire_get_neighbors",
            "Get all 6 neighboring hex cells of a given coordinate.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "q":{"type":"integer"},
              "r":{"type":"integer"}
            },"required":["worldId","q","r"]}""");

        register("goatmosire_query_radius",
            "Query all hex cells within a given radius of a center coordinate.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "q":{"type":"integer"},
              "r":{"type":"integer"},
              "radius":{"type":"integer","description":"Search radius in hex steps"}
            },"required":["worldId","q","r","radius"]}""");

        register("goatmosire_get_cities",
            "List all cities on the map with their coordinates.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"}
            },"required":["worldId"]}""");

        register("goatmosire_get_diff",
            "Get the map changes (diff) for a specific node. Shows what changed this turn.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"}
            },"required":["worldId","nodeId"]}""");

        register("goatmosire_get_history",
            "Get the map history across all nodes in the chain.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"}
            },"required":["worldId"]}""");

        register("goatmosire_list_worlds",
            "List all GSim worlds that have map data.",
            """
            {"type":"object","properties":{},"required":[]}""");

        register("goatmosire_find_river_path",
            "Find the minimum-cost river path from a source hex to the nearest water or map edge. Uses terrain moveCost as edge weight.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "q":{"type":"integer"},
              "r":{"type":"integer"}
            },"required":["worldId","q","r"]}""");

        register("goatmosire_list_regions",
            "List all regions with center coordinates, terrain composition, and adjacent region relationships.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"}
            },"required":["worldId"]}""");

        register("goatmosire_get_distance",
            "Calculate hex distance between two points (by coordinates or region names).",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "fromQ":{"type":"integer"},
              "fromR":{"type":"integer"},
              "toQ":{"type":"integer"},
              "toR":{"type":"integer"},
              "fromRegion":{"type":"string"},
              "toRegion":{"type":"string"}
            },"required":["worldId"]}""");
    }

    private void register(String name, String description, String schema) {
        tools.put(name, new ToolDef(name, description, schema));
    }

    // ── Tool implementations ──────────────────────────────

    private String handleGetHex(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        int q = args.get("q").asInt();
        int r = args.get("r").asInt();
        MapData map = nodeId != null ? mapService.resolve(worldId, nodeId) : mapService.resolveActive(worldId);
        String key = MapData.hexKey(q, r);
        MapData.HexCell cell = map.hexes().get(key);
        if (cell == null) return toJson(Map.of("found", false, "q", q, "r", r));
        // Find owning province
        String province = map.provinces().entrySet().stream()
            .filter(e -> e.getValue().hexes().contains(key))
            .map(Map.Entry::getKey).findFirst().orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("q", q); result.put("r", r);
        result.put("color", cell.color()); result.put("terrain", cell.terrain());
        if (cell.symbol() != null) result.put("symbol", cell.symbol());
        if (cell.description() != null && !cell.description().isEmpty()) result.put("description", cell.description());
        if (province != null) result.put("province", province);
        // Include terrain properties
        MapData.TerrainType tt = map.terrainTypes().get(cell.terrain());
        if (tt != null) {
            result.put("food", tt.food()); result.put("gold", tt.gold()); result.put("stone", tt.stone());
            result.put("moveCost", tt.moveCost());
        }
        return toJson(result);
    }

    private String handleGetProvince(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        String name = args.get("name").asText();
        MapData map = nodeId != null ? mapService.resolve(worldId, nodeId) : mapService.resolveActive(worldId);
        MapData.Province prov = map.provinces().get(name);
        if (prov == null) return toJson(Map.of("found", false, "name", name));
        List<Map<String, Object>> hexes = new ArrayList<>();
        for (String key : prov.hexes()) {
            MapData.HexCell cell = map.hexes().get(key);
            int[] coords = MapData.parseHexKey(key);
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("q", coords[0]); h.put("r", coords[1]);
            if (cell != null) {
                h.put("color", cell.color()); h.put("terrain", cell.terrain());
                if (cell.symbol() != null) h.put("symbol", cell.symbol());
            }
            hexes.add(h);
        }
        // Build adjacency index for all regions
        Map<String, Set<String>> regionHexSets = new LinkedHashMap<>();
        for (var e : map.provinces().entrySet()) {
            regionHexSets.put(e.getKey(), new HashSet<>(e.getValue().hexes()));
        }
        Set<String> ownSet = regionHexSets.get(name);
        List<Map<String, Object>> adj = ownSet != null
            ? computeAdjacency(name, ownSet, regionHexSets) : List.of();
        int[] center = computeCenter(prov.hexes(), map);
        Map<String, Integer> terrainComp = computeTerrainComposition(prov, map);

        return toJson(Map.of("found", true, "name", name, "hexCount", hexes.size(), "hexes", hexes,
            "tag", prov.tag() != null ? prov.tag() : "",
            "description", prov.description() != null ? prov.description() : "",
            "center", Map.of("q", center[0], "r", center[1]),
            "adjacentRegions", adj,
            "adjacentCount", adj.size(),
            "terrainComposition", terrainComp));
    }

    private String handleGetNeighbors(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        int q = args.get("q").asInt();
        int r = args.get("r").asInt();
        MapData map = nodeId != null ? mapService.resolve(worldId, nodeId) : mapService.resolveActive(worldId);
        int[][] dirs = {{1,0},{1,-1},{0,-1},{-1,0},{-1,1},{0,1}};
        List<Map<String, Object>> neighbors = new ArrayList<>();
        for (int[] d : dirs) {
            String key = MapData.hexKey(q + d[0], r + d[1]);
            MapData.HexCell cell = map.hexes().get(key);
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("q", q + d[0]); n.put("r", r + d[1]);
            n.put("exists", cell != null);
            if (cell != null) {
                n.put("color", cell.color()); n.put("terrain", cell.terrain());
            }
            neighbors.add(n);
        }
        return toJson(Map.of("center", Map.of("q",q,"r",r), "neighbors", neighbors));
    }

    private String handleQueryRadius(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        int cq = args.get("q").asInt();
        int cr = args.get("r").asInt();
        int radius = args.get("radius").asInt();
        MapData map = nodeId != null ? mapService.resolve(worldId, nodeId) : mapService.resolveActive(worldId);
        List<Map<String, Object>> results = new ArrayList<>();
        for (int dq = -radius; dq <= radius; dq++) {
            for (int dr = Math.max(-radius, -dq - radius); dr <= Math.min(radius, -dq + radius); dr++) {
                String key = MapData.hexKey(cq + dq, cr + dr);
                MapData.HexCell cell = map.hexes().get(key);
                if (cell != null) {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("q", cq + dq); h.put("r", cr + dr);
                    h.put("color", cell.color()); h.put("terrain", cell.terrain());
                    results.add(h);
                }
            }
        }
        return toJson(Map.of("center", Map.of("q",cq,"r",cr), "radius", radius, "count", results.size(), "hexes", results));
    }

    private String handleGetCities(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        MapData map = nodeId != null ? mapService.resolve(worldId, nodeId) : mapService.resolveActive(worldId);
        List<Map<String, Object>> cities = new ArrayList<>();
        for (var e : map.cities().entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", e.getKey());
            c.put("q", e.getValue().q()); c.put("r", e.getValue().r());
            String key = MapData.hexKey(e.getValue().q(), e.getValue().r());
            MapData.HexCell cell = map.hexes().get(key);
            if (cell != null) c.put("terrain", cell.terrain());
            cities.add(c);
        }
        return toJson(Map.of("cities", cities));
    }

    private String handleGetDiff(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        String nodeId = args.get("nodeId").asText();
        MapDiff diff = MapStore.loadDiff(
            mapService.getWorldsDir(),
            worldId, nodeId);
        if (diff == null) return toJson(Map.of("hasDiff", false, "nodeId", nodeId));
        return toJson(Map.of("hasDiff", true, "nodeId", nodeId,
            "changedCount", diff.changed().size(),
            "removedCount", diff.removed().size(),
            "changed", diff.changed().keySet(),
            "removed", diff.removed()));
    }

    private String handleGetHistory(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        if (nodeId == null) {
            var worldsDir = mapService.getWorldsDir();
            var activeFile = worldsDir.resolve(worldId).resolve("active.json");
            if (java.nio.file.Files.exists(activeFile)) {
                var n = MAPPER.readTree(activeFile.toFile());
                if (n.has("nodeId")) nodeId = n.get("nodeId").asText();
            }
        }
        if (nodeId == null) return toJson(Map.of("error", "No active node"));
        List<MapResolver.HistoryEntry> history = mapService.history(worldId, nodeId);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (var h : history) {
            entries.add(Map.of("nodeId", h.nodeId(), "hasOwnMap", h.hasOwnMap(),
                "hexCount", h.map().hexes().size()));
        }
        return toJson(Map.of("worldId", worldId, "chain", entries));
    }

    private String handleListWorlds(JsonNode args) throws Exception {
        List<String> worlds = mapService.listWorldsWithMaps();
        return toJson(Map.of("worlds", worlds));
    }

    private String handleFindRiverPath(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        int q = args.get("q").asInt();
        int r = args.get("r").asInt();
        List<String> path = mapService.findRiverPath(worldId, nodeId, q, r);
        return toJson(Map.of("source", Map.of("q", q, "r", r), "path", path, "length", path.size()));
    }

    private String handleListRegions(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        MapData map = nodeId != null ? mapService.resolve(worldId, nodeId) : mapService.resolveActive(worldId);

        if (map.provinces() == null || map.provinces().isEmpty()) {
            return toJson(Map.of("worldId", worldId, "regions", List.of(), "count", 0));
        }

        // Build adjacencies: for each region, find touching regions
        Map<String, Set<String>> regionHexSets = new LinkedHashMap<>();
        Map<String, MapData.Province> provs = map.provinces();
        for (var entry : provs.entrySet()) {
            regionHexSets.put(entry.getKey(), new HashSet<>(entry.getValue().hexes()));
        }

        List<Map<String, Object>> regions = new ArrayList<>();
        for (var entry : provs.entrySet()) {
            String name = entry.getKey();
            MapData.Province prov = entry.getValue();
            Set<String> hexSet = regionHexSets.get(name);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", name);
            r.put("tag", prov.tag() != null ? prov.tag() : "");
            r.put("hexCount", prov.hexes().size());

            // Center
            int[] center = computeCenter(prov.hexes(), map);
            r.put("center", Map.of("q", center[0], "r", center[1]));

            // Terrain composition
            r.put("terrainComposition", computeTerrainComposition(prov, map));

            // Adjacent regions
            List<Map<String, Object>> adj = computeAdjacency(name, hexSet, regionHexSets);
            r.put("adjacentRegions", adj);
            r.put("adjacentCount", adj.size());

            regions.add(r);
        }

        return toJson(Map.of("worldId", worldId, "regions", regions, "count", regions.size()));
    }

    private String handleGetDistance(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        MapData map = nodeId != null ? mapService.resolve(worldId, nodeId) : mapService.resolveActive(worldId);

        int fromQ, fromR, toQ, toR;
        String fromLabel, toLabel;

        if (args.has("fromRegion") && args.has("toRegion")) {
            MapData.Province fromP = map.provinces().get(args.get("fromRegion").asText());
            MapData.Province toP = map.provinces().get(args.get("toRegion").asText());
            if (fromP == null || toP == null)
                return toJson(Map.of("error", "Region not found"));
            int[] fc = computeCenter(fromP.hexes(), map);
            int[] tc = computeCenter(toP.hexes(), map);
            fromQ = fc[0]; fromR = fc[1]; toQ = tc[0]; toR = tc[1];
            fromLabel = args.get("fromRegion").asText();
            toLabel = args.get("toRegion").asText();
        } else if (args.has("fromQ") && args.has("toQ")) {
            fromQ = args.get("fromQ").asInt();
            fromR = args.get("fromR").asInt();
            toQ = args.get("toQ").asInt();
            toR = args.get("toR").asInt();
            fromLabel = "(" + fromQ + "," + fromR + ")";
            toLabel = "(" + toQ + "," + toR + ")";
        } else {
            return toJson(Map.of("error", "Provide (fromQ,fromR,toQ,toR) or (fromRegion,toRegion)"));
        }

        int hexDist = hexDistance(fromQ, fromR, toQ, toR);
        return toJson(Map.of(
            "from", fromLabel, "to", toLabel,
            "fromCoord", Map.of("q", fromQ, "r", fromR),
            "toCoord", Map.of("q", toQ, "r", toR),
            "hexDistance", hexDist));
    }

    // ── Geometry helpers ───────────────────────────────────

    private static int hexDistance(int q1, int r1, int q2, int r2) {
        return (Math.abs(q1 - q2) + Math.abs(r1 - r2) + Math.abs((-q1 - r1) - (-q2 - r2))) / 2;
    }

    private static int[] computeCenter(List<String> hexes, MapData map) {
        if (hexes == null || hexes.isEmpty()) return new int[]{0, 0};
        int sq = 0, sr = 0;
        for (String hk : hexes) {
            int[] qr = MapData.parseHexKey(hk);
            sq += qr[0]; sr += qr[1];
        }
        return new int[]{Math.round((float) sq / hexes.size()), Math.round((float) sr / hexes.size())};
    }

    private static Map<String, Integer> computeTerrainComposition(MapData.Province prov, MapData map) {
        Map<String, Integer> comp = new LinkedHashMap<>();
        if (prov.hexes() == null || map.hexes() == null) return comp;
        for (String hk : prov.hexes()) {
            MapData.HexCell cell = map.hexes().get(hk);
            if (cell != null) comp.merge(cell.terrain(), 1, Integer::sum);
        }
        return comp;
    }

    private static final int[][] DIRS = {{1,0},{1,-1},{0,-1},{-1,0},{-1,1},{0,1}};

    private static List<Map<String, Object>> computeAdjacency(
            String ownName, Set<String> ownHexes, Map<String, Set<String>> allRegionHexes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : allRegionHexes.entrySet()) {
            String otherName = entry.getKey();
            if (otherName.equals(ownName)) continue;
            Set<String> otherHexes = entry.getValue();

            int sharedEdges = 0;
            for (String hk : ownHexes) {
                int[] qr = MapData.parseHexKey(hk);
                for (int[] d : DIRS) {
                    String nk = MapData.hexKey(qr[0] + d[0], qr[1] + d[1]);
                    if (otherHexes.contains(nk)) sharedEdges++;
                }
            }
            if (sharedEdges > 0) {
                result.add(Map.of("name", otherName, "sharedEdges", sharedEdges));
            }
        }
        return result;
    }

    private static String toJson(Object obj) throws Exception {
        return MAPPER.writeValueAsString(obj);
    }
}

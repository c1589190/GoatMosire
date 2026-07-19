package com.goatmosire.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goatmosire.map.MapData;
import com.goatmosire.map.MapDiff;
import com.goatmosire.map.MapResolver;
import com.goatmosire.map.MapStore;
import com.goatmosire.service.MapService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry of MCP tools exposed by GoatMosire.
 * All tools are prefixed "goatmosire_" for Hermes auto-discovery.
 */
public class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MapService mapService;
    private final Map<String, ToolDef> tools = new LinkedHashMap<>();

    private static final Random RANDOM = new Random();

    /**
     * Creates a tool registry and registers all goatmosire_* MCP tools.
     *
     * @param mapService the shared map service instance
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2") // MapService is a shared service class, not a data object
    public McpToolRegistry(MapService mapService) {
        this.mapService = mapService;
        registerAll();
    }

    public record ToolDef(String name, String description, String schema) {}

    /**
     * Returns an immutable snapshot of all registered tool definitions.
     *
     * @return list of all tool definitions
     */
    public List<ToolDef> all() {
        return List.copyOf(tools.values());
    }

    /**
     * Execute the named tool with the given JSON arguments.
     *
     * @param name the tool name (must be registered)
     * @param args the JSON arguments
     * @return JSON result string
     * @throws IOException if JSON serialization fails
     * @throws IllegalArgumentException if the tool name is unknown
     */
    public String execute(String name, JsonNode args) throws IOException {
        ToolDef tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("Unknown tool: " + name);
        return switch (name) {
            case "goatmosire_get_hex" -> handleGetHex(args);
            case "goatmosire_get_province" -> handleGetProvince(args);
            case "goatmosire_get_neighbors" -> handleGetNeighbors(args);
            case "goatmosire_query_radius" -> handleQueryRadius(args);
            case "goatmosire_get_cities" -> handleGetCities(args);
            case "goatmosire_get_diff" -> handleGetDiff(args);
            case "goatmosire_get_history" -> handleGetHistory(args);
            case "goatmosire_find_river_path" -> handleFindRiverPath(args);
            case "goatmosire_list_regions" -> handleListRegions(args);
            case "goatmosire_get_distance" -> handleGetDistance(args);
            case "goatmosire_update_region" -> handleUpdateRegion(args);
            case "goatmosire_add_hex_to_region" -> handleAddHexToRegion(args);
            case "goatmosire_remove_hex_from_region" -> handleRemoveHexFromRegion(args);
            case "goatmosire_create_region" -> handleCreateRegion(args);
            case "goatmosire_delete_region" -> handleDeleteRegion(args);
            case "goatmosire_list_checkpoints" -> handleListCheckpoints(args);
            case "goatmosire_get_checkpoint" -> handleGetCheckpoint(args);
            case "goatmosire_add_checkpoint_element" -> handleAddCheckpointElement(args);
            case "goatmosire_update_checkpoint_element" -> handleUpdateCheckpointElement(args);
            case "goatmosire_delete_checkpoint_element" -> handleDeleteCheckpointElement(args);
            case "goatmosire_rename_region" -> handleRenameRegion(args);
            case "goatmosire_init_nation" -> handleInitNation(args);
            case "goatmosire_update_terrain_type" -> handleUpdateTerrainType(args);
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
    }

    // ── Registration ──────────────────────────────────────

    private void registerAll() {
        registerQueryTools();
        registerDiffTools();
        registerRegionTools();
        registerCheckpointTools();
        registerInitTools();
    }

    private void registerQueryTools() {
        register(
                "goatmosire_get_hex",
                "Query a single hex cell by coordinates. Returns color, terrain type, symbol, and province ownership.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string","description":"GSim world ID"},
              "nodeId":{"type":"string","description":"Node ID (optional, defaults to active node)"},
              "q":{"type":"integer","description":"Axial q coordinate"},
              "r":{"type":"integer","description":"Axial r coordinate"}
            },"required":["worldId","q","r"]}""");

        register(
                "goatmosire_get_province",
                "Query a province by name. Returns all hex cells belonging to it.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "name":{"type":"string","description":"Province name"}
            },"required":["worldId","name"]}""");

        register(
                "goatmosire_get_neighbors",
                "Get all 6 neighboring hex cells of a given coordinate.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "q":{"type":"integer"},
              "r":{"type":"integer"}
            },"required":["worldId","q","r"]}""");

        register(
                "goatmosire_query_radius",
                "Query all hex cells within a given radius of a center coordinate.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "q":{"type":"integer"},
              "r":{"type":"integer"},
              "radius":{"type":"integer","description":"Search radius in hex steps"}
            },"required":["worldId","q","r","radius"]}""");

        register(
                "goatmosire_get_cities",
                "List all cities on the map with their coordinates.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"}
            },"required":["worldId"]}""");

        register(
                "goatmosire_find_river_path",
                "Find the minimum-cost river path from a source hex to the nearest water "
                        + "or map edge. Uses terrain moveCost as edge weight.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "q":{"type":"integer"},
              "r":{"type":"integer"}
            },"required":["worldId","q","r"]}""");

        register(
                "goatmosire_list_regions",
                "List all regions with center coordinates, terrain composition, "
                        + "and adjacent region relationships.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"}
            },"required":["worldId"]}""");

        register(
                "goatmosire_get_distance",
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

    private void registerDiffTools() {
        register(
                "goatmosire_get_diff",
                "Get the map changes (diff) for a specific node. Shows what changed this turn.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"}
            },"required":["worldId","nodeId"]}""");

        register(
                "goatmosire_get_history",
                "Get the map history across all nodes in the chain.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"}
            },"required":["worldId"]}""");
    }

    private void registerRegionTools() {
        register(
                "goatmosire_update_region",
                "Update a region's properties (hexes, tag, description, color). " + "Auto-saves after change.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "name":{"type":"string","description":"Region name"},
              "tag":{"type":"string","description":"New tag (optional)"},
              "description":{"type":"string","description":"New description (optional)"},
              "color":{"type":"string","description":"New hex color (optional)"},
              "hexes":{"type":"array",
                "items":{"type":"string"},
                "description":"New hex key list e.g. ['10_-5','11_-5'] (optional)"}
            },"required":["worldId","name"]}""");

        register(
                "goatmosire_add_hex_to_region",
                "Add a single hex to a region. Auto-saves after change.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "name":{"type":"string","description":"Region name"},
              "q":{"type":"integer"},
              "r":{"type":"integer"}
            },"required":["worldId","name","q","r"]}""");

        register(
                "goatmosire_remove_hex_from_region",
                "Remove a single hex from a region. Auto-saves after change.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "name":{"type":"string","description":"Region name"},
              "q":{"type":"integer"},
              "r":{"type":"integer"}
            },"required":["worldId","name","q","r"]}""");

        register(
                "goatmosire_create_region",
                "Create a new empty region. Auto-saves.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "name":{"type":"string","description":"New region name"},
              "tag":{"type":"string","description":"Tag (optional)"},
              "color":{"type":"string","description":"Hex color (optional, default auto-generated)"},
              "description":{"type":"string","description":"Description (optional)"},
              "hexes":{"type":"array","items":{"type":"string"},
                "description":"Initial hex keys (optional, default empty)"}
            },"required":["worldId","name"]}""");

        register(
                "goatmosire_delete_region",
                "Delete a region. Auto-saves.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "name":{"type":"string","description":"Region name to delete"}
            },"required":["worldId","name"]}""");

        register(
                "goatmosire_rename_region",
                "Rename a region across all data stores: MapData provinces + all GSim "
                        + "checkpoint references (factions, narrative, map, etc.). "
                        + "Updates keys, tags, and text references. Auto-saves.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "oldName":{"type":"string","description":"Current region name"},
              "newName":{"type":"string","description":"New region name"}
            },"required":["worldId","oldName","newName"]}""");
    }

    private void registerCheckpointTools() {
        // ── Checkpoint (document) tools ───────────────────

        register(
                "goatmosire_list_checkpoints",
                "List all checkpoints in a GSim node (narrative, factions, worldview, "
                        + "characters, map). Returns name, label, type, and element count for each.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string","description":"Node ID (optional, defaults to active node)"}
            },"required":["worldId"]}""");

        register(
                "goatmosire_get_checkpoint",
                "Get elements from a GSim checkpoint. Optionally filter by element key or tags. "
                        + "Use this to read narrative entries, faction descriptions, worldview docs, "
                        + "or character states.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "checkpoint":{"type":"string",
                "description":"Checkpoint name: narrative, factions, worldview, characters, map"},
              "key":{"type":"string","description":"Filter by specific element key (optional)"},
              "tags":{"type":"array","items":{"type":"string"},
                "description":"Filter by tags — element must have ALL specified tags (optional)"}
            },"required":["worldId","checkpoint"]}""");

        register(
                "goatmosire_add_checkpoint_element",
                "Add a new element to a GSim checkpoint. Use to create narrative entries, "
                        + "faction descriptions, character states, or worldview documents.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "checkpoint":{"type":"string",
                "description":"Checkpoint name: narrative, factions, worldview, characters, map"},
              "key":{"type":"string","description":"Unique element key (e.g. '大汉开局')"},
              "type":{"type":"string",
                "description":"Element type: text, character_state, map-region, map-city (default: text)"},
              "value":{"type":"string","description":"Full text content of the element"},
              "tags":{"type":"array","items":{"type":"string"},
                "description":"Tags for categorization and filtering (e.g. ['开局','大汉','推文'])"}
            },"required":["worldId","checkpoint","key","value"]}""");

        register(
                "goatmosire_update_checkpoint_element",
                "Update an existing element in a GSim checkpoint. " + "Only provided fields are changed.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "checkpoint":{"type":"string","description":"Checkpoint name"},
              "key":{"type":"string","description":"Element key to update"},
              "value":{"type":"string","description":"New text content (optional)"},
              "type":{"type":"string","description":"New element type (optional)"},
              "tags":{"type":"array","items":{"type":"string"},
                "description":"New tags list (optional, replaces all existing tags)"}
            },"required":["worldId","checkpoint","key"]}""");

        register(
                "goatmosire_delete_checkpoint_element",
                "Delete an element from a GSim checkpoint by key.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "checkpoint":{"type":"string","description":"Checkpoint name"},
              "key":{"type":"string","description":"Element key to delete"}
            },"required":["worldId","checkpoint","key"]}""");
    }

    private void registerInitTools() {
        register(
                "goatmosire_init_nation",
                "One-shot nation initialization: flood-fill unowned hexes from a seed, "
                        + "create the MapData province, sync to GSim map checkpoint, and optionally "
                        + "write faction/narrative/worldview checkpoint entries and a capital city. "
                        + "Use this to bootstrap countries in unexplored territory.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "name":{"type":"string","description":"Nation/province name"},
              "seedQ":{"type":"integer","description":"Seed hex q (flood-fill start point)"},
              "seedR":{"type":"integer","description":"Seed hex r"},
              "maxHexes":{"type":"integer","description":"Max hexes to collect (default 1000)"},
              "tag":{"type":"string","description":"Region tag (default: 'Nation')"},
              "color":{"type":"string","description":"Region color hex (default: auto-generated)"},
              "faction":{"type":"string","description":"Faction description text → factions checkpoint"},
              "narrative":{"type":"string","description":"Opening narrative text → narrative checkpoint"},
              "worldview":{"type":"string","description":"Worldview text → worldview checkpoint (optional)"},
              "capital":{"type":"string","description":"Capital city name → creates city element in map checkpoint"},
              "ruler":{"type":"string","description":"Ruler name (optional, appended to faction tags)"},
              "religion":{"type":"string","description":"Religion (optional, appended to faction tags)"}
            },"required":["worldId","name","seedQ","seedR"]}""");

        register(
                "goatmosire_update_terrain_type",
                "Update a terrain type definition (name, color, food, gold, stone, moveCost, "
                        + "description). Provide only the fields you want to change.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string","description":"GSim world ID"},
              "nodeId":{"type":"string","description":"Node ID (optional, defaults to active node)"},
              "key":{"type":"string",
                "description":"Terrain key: water, lowland, hills, plains, mountain, swamp, desert, tundra, forest"},
              "name":{"type":"string","description":"New display name (e.g. '山区')"},
              "color":{"type":"string","description":"New hex color (e.g. '#B8A88A')"},
              "food":{"type":"integer","description":"Food output"},
              "gold":{"type":"integer","description":"Gold output"},
              "stone":{"type":"integer","description":"Stone output"},
              "moveCost":{"type":"integer","description":"Movement cost"},
              "description":{"type":"string","description":"Tooltip description"}
            },"required":["worldId","key"]}""");
    }

    private void register(String name, String description, String schema) {
        tools.put(name, new ToolDef(name, description, schema));
    }

    // ── Tool implementations ──────────────────────────────

    private String handleGetHex(JsonNode args) throws IOException {
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
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("q", q);
        result.put("r", r);
        result.put("color", cell.color());
        result.put("terrain", cell.terrain());
        if (cell.symbol() != null) result.put("symbol", cell.symbol());
        if (cell.description() != null && !cell.description().isEmpty()) result.put("description", cell.description());
        if (province != null) result.put("province", province);
        // Include terrain properties
        MapData.TerrainType tt = map.terrainTypes().get(cell.terrain());
        if (tt != null) {
            result.put("food", tt.food());
            result.put("gold", tt.gold());
            result.put("stone", tt.stone());
            result.put("moveCost", tt.moveCost());
        }
        return toJson(result);
    }

    private String handleGetProvince(JsonNode args) throws IOException {
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
            h.put("q", coords[0]);
            h.put("r", coords[1]);
            if (cell != null) {
                h.put("color", cell.color());
                h.put("terrain", cell.terrain());
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
        List<Map<String, Object>> adj = ownSet != null ? computeAdjacency(name, ownSet, regionHexSets) : List.of();
        int[] center = computeCenter(prov.hexes(), map);
        Map<String, Integer> terrainComp = computeTerrainComposition(prov, map);

        return toJson(Map.of(
                "found",
                true,
                "name",
                name,
                "hexCount",
                hexes.size(),
                "hexes",
                hexes,
                "tag",
                prov.tag() != null ? prov.tag() : "",
                "description",
                prov.description() != null ? prov.description() : "",
                "center",
                Map.of("q", center[0], "r", center[1]),
                "adjacentRegions",
                adj,
                "adjacentCount",
                adj.size(),
                "terrainComposition",
                terrainComp));
    }

    private String handleGetNeighbors(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        int q = args.get("q").asInt();
        int r = args.get("r").asInt();
        MapData map = nodeId != null ? mapService.resolve(worldId, nodeId) : mapService.resolveActive(worldId);
        int[][] dirs = {{1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}};
        List<Map<String, Object>> neighbors = new ArrayList<>();
        for (int[] d : dirs) {
            String key = MapData.hexKey(q + d[0], r + d[1]);
            MapData.HexCell cell = map.hexes().get(key);
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("q", q + d[0]);
            n.put("r", r + d[1]);
            n.put("exists", cell != null);
            if (cell != null) {
                n.put("color", cell.color());
                n.put("terrain", cell.terrain());
            }
            neighbors.add(n);
        }
        return toJson(Map.of("center", Map.of("q", q, "r", r), "neighbors", neighbors));
    }

    private String handleQueryRadius(JsonNode args) throws IOException {
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
                    h.put("q", cq + dq);
                    h.put("r", cr + dr);
                    h.put("color", cell.color());
                    h.put("terrain", cell.terrain());
                    results.add(h);
                }
            }
        }
        return toJson(Map.of(
                "center", Map.of("q", cq, "r", cr), "radius", radius, "count", results.size(), "hexes", results));
    }

    private String handleGetCities(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        MapData map = nodeId != null ? mapService.resolve(worldId, nodeId) : mapService.resolveActive(worldId);
        List<Map<String, Object>> cities = new ArrayList<>();
        for (var e : map.cities().entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", e.getKey());
            c.put("q", e.getValue().q());
            c.put("r", e.getValue().r());
            String key = MapData.hexKey(e.getValue().q(), e.getValue().r());
            MapData.HexCell cell = map.hexes().get(key);
            if (cell != null) c.put("terrain", cell.terrain());
            cities.add(c);
        }
        return toJson(Map.of("cities", cities));
    }

    private String handleGetDiff(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.get("nodeId").asText();
        MapDiff diff = MapStore.loadDiff(mapService.getWorldsDir(), worldId, nodeId);
        if (diff == null) return toJson(Map.of("hasDiff", false, "nodeId", nodeId));
        return toJson(Map.of(
                "hasDiff",
                true,
                "nodeId",
                nodeId,
                "changedCount",
                diff.changed().size(),
                "removedCount",
                diff.removed().size(),
                "changed",
                diff.changed().keySet(),
                "removed",
                diff.removed()));
    }

    private String handleGetHistory(JsonNode args) throws IOException {
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
            entries.add(Map.of(
                    "nodeId",
                    h.nodeId(),
                    "hasOwnMap",
                    h.hasOwnMap(),
                    "hexCount",
                    h.map().hexes().size()));
        }
        return toJson(Map.of("worldId", worldId, "chain", entries));
    }

    private String handleFindRiverPath(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        int q = args.get("q").asInt();
        int r = args.get("r").asInt();
        List<String> path = mapService.findRiverPath(worldId, nodeId, q, r);
        return toJson(Map.of("source", Map.of("q", q, "r", r), "path", path, "length", path.size()));
    }

    private String handleListRegions(JsonNode args) throws IOException {
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

    private String handleGetDistance(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        MapData map = nodeId != null ? mapService.resolve(worldId, nodeId) : mapService.resolveActive(worldId);

        int fromQ;
        int fromR;
        int toQ;
        int toR;
        String fromLabel;
        String toLabel;

        if (args.has("fromRegion") && args.has("toRegion")) {
            MapData.Province fromP = map.provinces().get(args.get("fromRegion").asText());
            MapData.Province toP = map.provinces().get(args.get("toRegion").asText());
            if (fromP == null || toP == null) return toJson(Map.of("error", "Region not found"));
            int[] fc = computeCenter(fromP.hexes(), map);
            int[] tc = computeCenter(toP.hexes(), map);
            fromQ = fc[0];
            fromR = fc[1];
            toQ = tc[0];
            toR = tc[1];
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
                "from",
                fromLabel,
                "to",
                toLabel,
                "fromCoord",
                Map.of("q", fromQ, "r", fromR),
                "toCoord",
                Map.of("q", toQ, "r", toR),
                "hexDistance",
                hexDist));
    }

    // ── Geometry helpers ───────────────────────────────────

    private static int hexDistance(int q1, int r1, int q2, int r2) {
        return (Math.abs(q1 - q2) + Math.abs(r1 - r2) + Math.abs((-q1 - r1) - (-q2 - r2))) / 2;
    }

    private static int[] computeCenter(List<String> hexes, MapData map) {
        if (hexes == null || hexes.isEmpty()) return new int[] {0, 0};
        int sq = 0;
        int sr = 0;
        for (String hk : hexes) {
            int[] qr = MapData.parseHexKey(hk);
            sq += qr[0];
            sr += qr[1];
        }
        return new int[] {Math.round((float) sq / hexes.size()), Math.round((float) sr / hexes.size())};
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

    private static final int[][] DIRS = {{1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}};

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

    // ── Write tools ───────────────────────────────────────

    private String handleUpdateRegion(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : "n0000";
        String name = args.get("name").asText();
        MapData map = mapService.resolve(worldId, nodeId);
        MapData.Province prov = map.provinces().get(name);
        if (prov == null) return toJson(Map.of("ok", false, "error", "Region not found: " + name));

        // Read optional fields
        String newTag = args.has("tag") ? args.get("tag").asText() : prov.tag();
        String newDesc = args.has("description") ? args.get("description").asText() : prov.description();
        String newColor = args.has("color") ? args.get("color").asText() : prov.color();
        List<String> newHexes = prov.hexes();
        if (args.has("hexes")) {
            newHexes = new ArrayList<>();
            for (JsonNode n : args.get("hexes")) {
                newHexes.add(n.asText());
            }
        }

        // Build updated province
        Map<String, MapData.Province> updatedProvinces = new LinkedHashMap<>(map.provinces());
        updatedProvinces.put(name, new MapData.Province(newHexes, newColor, newTag, newDesc));

        // Rebuild MapData with updated provinces
        MapData updated = new MapData(
                map.gridSize(),
                map.hexOrientation(),
                map.hexes(),
                map.terrainBlocks(),
                updatedProvinces,
                map.cities(),
                map.rivers(),
                map.roads(),
                map.terrainTypes(),
                map.compressedRegions(),
                map.pathwayGroups());
        mapService.saveFull(worldId, nodeId, updated);
        mapService.syncToGSimNode(worldId, nodeId);

        return toJson(
                Map.of("ok", true, "name", name, "hexCount", newHexes.size(), "tag", newTag, "description", newDesc));
    }

    private String handleAddHexToRegion(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : "n0000";
        String name = args.get("name").asText();
        int q = args.get("q").asInt();
        int r = args.get("r").asInt();
        MapData map = mapService.resolve(worldId, nodeId);
        MapData.Province prov = map.provinces().get(name);
        if (prov == null) return toJson(Map.of("ok", false, "error", "Region not found: " + name));

        String hexKey = MapData.hexKey(q, r);
        if (!map.hexes().containsKey(hexKey)) return toJson(Map.of("ok", false, "error", "Hex not on map: " + hexKey));

        List<String> newHexes = new ArrayList<>(prov.hexes());
        if (newHexes.contains(hexKey))
            return toJson(
                    Map.of("ok", true, "name", name, "hexCount", newHexes.size(), "note", "hex already in region"));

        newHexes.add(hexKey);
        Map<String, MapData.Province> updatedProvinces = new LinkedHashMap<>(map.provinces());
        updatedProvinces.put(name, new MapData.Province(newHexes, prov.color(), prov.tag(), prov.description()));
        MapData updated = new MapData(
                map.gridSize(),
                map.hexOrientation(),
                map.hexes(),
                map.terrainBlocks(),
                updatedProvinces,
                map.cities(),
                map.rivers(),
                map.roads(),
                map.terrainTypes(),
                map.compressedRegions(),
                map.pathwayGroups());
        mapService.saveFull(worldId, nodeId, updated);
        mapService.syncToGSimNode(worldId, nodeId);

        return toJson(Map.of("ok", true, "name", name, "hexCount", newHexes.size(), "added", hexKey));
    }

    private String handleRemoveHexFromRegion(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : "n0000";
        String name = args.get("name").asText();
        int q = args.get("q").asInt();
        int r = args.get("r").asInt();
        MapData map = mapService.resolve(worldId, nodeId);
        MapData.Province prov = map.provinces().get(name);
        if (prov == null) return toJson(Map.of("ok", false, "error", "Region not found: " + name));

        String hexKey = MapData.hexKey(q, r);
        List<String> newHexes = new ArrayList<>(prov.hexes());
        if (!newHexes.remove(hexKey))
            return toJson(
                    Map.of("ok", true, "name", name, "hexCount", newHexes.size(), "note", "hex was not in region"));

        Map<String, MapData.Province> updatedProvinces = new LinkedHashMap<>(map.provinces());
        updatedProvinces.put(name, new MapData.Province(newHexes, prov.color(), prov.tag(), prov.description()));
        MapData updated = new MapData(
                map.gridSize(),
                map.hexOrientation(),
                map.hexes(),
                map.terrainBlocks(),
                updatedProvinces,
                map.cities(),
                map.rivers(),
                map.roads(),
                map.terrainTypes(),
                map.compressedRegions(),
                map.pathwayGroups());
        mapService.saveFull(worldId, nodeId, updated);
        mapService.syncToGSimNode(worldId, nodeId);

        return toJson(Map.of("ok", true, "name", name, "hexCount", newHexes.size(), "removed", hexKey));
    }

    private String handleCreateRegion(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : "n0000";
        String name = args.get("name").asText();
        MapData map = mapService.resolve(worldId, nodeId);
        if (map.provinces().containsKey(name))
            return toJson(Map.of("ok", false, "error", "Region already exists: " + name));

        String tag = args.has("tag") ? args.get("tag").asText() : "";
        String desc = args.has("description") ? args.get("description").asText() : "";
        String color = args.has("color")
                ? args.get("color").asText()
                : String.format("#%06x", RANDOM.nextInt(0xFFFFFF) | 0x404040);
        List<String> hexes = new ArrayList<>();
        if (args.has("hexes")) {
            for (JsonNode n : args.get("hexes")) {
                hexes.add(n.asText());
            }
        }

        Map<String, MapData.Province> updatedProvinces = new LinkedHashMap<>(map.provinces());
        updatedProvinces.put(name, new MapData.Province(hexes, color, tag, desc));
        MapData updated = new MapData(
                map.gridSize(),
                map.hexOrientation(),
                map.hexes(),
                map.terrainBlocks(),
                updatedProvinces,
                map.cities(),
                map.rivers(),
                map.roads(),
                map.terrainTypes(),
                map.compressedRegions(),
                map.pathwayGroups());
        mapService.saveFull(worldId, nodeId, updated);
        mapService.syncToGSimNode(worldId, nodeId);

        return toJson(Map.of("ok", true, "name", name, "hexCount", hexes.size(), "tag", tag, "color", color));
    }

    private String handleDeleteRegion(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : "n0000";
        String name = args.get("name").asText();
        MapData map = mapService.resolve(worldId, nodeId);
        if (!map.provinces().containsKey(name))
            return toJson(Map.of("ok", false, "error", "Region not found: " + name));

        Map<String, MapData.Province> updatedProvinces = new LinkedHashMap<>(map.provinces());
        updatedProvinces.remove(name);
        MapData updated = new MapData(
                map.gridSize(),
                map.hexOrientation(),
                map.hexes(),
                map.terrainBlocks(),
                updatedProvinces,
                map.cities(),
                map.rivers(),
                map.roads(),
                map.terrainTypes(),
                map.compressedRegions(),
                map.pathwayGroups());
        mapService.saveFull(worldId, nodeId, updated);
        mapService.syncToGSimNode(worldId, nodeId);

        return toJson(Map.of("ok", true, "name", name, "action", "deleted"));
    }

    // ── Checkpoint tools ──────────────────────────────────

    private String handleListCheckpoints(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : readActiveNodeId(worldId);
        if (nodeId == null) return toJson(Map.of("error", "No active node for world: " + worldId));
        return toJson(mapService.getCheckpointService().listCheckpoints(worldId, nodeId));
    }

    private String handleGetCheckpoint(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : readActiveNodeId(worldId);
        if (nodeId == null) return toJson(Map.of("error", "No active node for world: " + worldId));
        String cp = args.get("checkpoint").asText();
        String key = args.has("key") ? args.get("key").asText() : null;
        List<String> tags = null;
        if (args.has("tags") && args.get("tags").isArray()) {
            tags = new ArrayList<>();
            for (JsonNode t : args.get("tags")) {
                tags.add(t.asText());
            }
        }
        return toJson(mapService.getCheckpointService().getCheckpoint(worldId, nodeId, cp, key, tags));
    }

    private String handleAddCheckpointElement(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : readActiveNodeId(worldId);
        if (nodeId == null) return toJson(Map.of("error", "No active node for world: " + worldId));
        String cp = args.get("checkpoint").asText();
        String key = args.get("key").asText();
        String value = args.get("value").asText();
        String type = args.has("type") ? args.get("type").asText() : "text";
        List<String> tags = null;
        if (args.has("tags") && args.get("tags").isArray()) {
            tags = new ArrayList<>();
            for (JsonNode t : args.get("tags")) {
                tags.add(t.asText());
            }
        }
        return toJson(mapService.getCheckpointService().addElement(worldId, nodeId, cp, key, type, value, tags));
    }

    private String handleUpdateCheckpointElement(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : readActiveNodeId(worldId);
        if (nodeId == null) return toJson(Map.of("error", "No active node for world: " + worldId));
        String cp = args.get("checkpoint").asText();
        String key = args.get("key").asText();
        String value = args.has("value") ? args.get("value").asText() : null;
        String type = args.has("type") ? args.get("type").asText() : null;
        List<String> tags = null;
        if (args.has("tags") && args.get("tags").isArray()) {
            tags = new ArrayList<>();
            for (JsonNode t : args.get("tags")) {
                tags.add(t.asText());
            }
        }
        return toJson(mapService.getCheckpointService().updateElement(worldId, nodeId, cp, key, value, type, tags));
    }

    private String handleDeleteCheckpointElement(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : readActiveNodeId(worldId);
        if (nodeId == null) return toJson(Map.of("error", "No active node for world: " + worldId));
        String cp = args.get("checkpoint").asText();
        String key = args.get("key").asText();
        return toJson(mapService.getCheckpointService().deleteElement(worldId, nodeId, cp, key));
    }

    private String handleRenameRegion(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : readActiveNodeId(worldId);
        if (nodeId == null) return toJson(Map.of("error", "No active node for world: " + worldId));
        String oldName = args.get("oldName").asText();
        String newName = args.get("newName").asText();
        return toJson(mapService.renameRegion(worldId, nodeId, oldName, newName));
    }

    private String handleInitNation(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : readActiveNodeId(worldId);
        if (nodeId == null) return toJson(Map.of("error", "No active node for world: " + worldId));
        String name = args.get("name").asText();
        int seedQ = args.get("seedQ").asInt();
        int seedR = args.get("seedR").asInt();
        int maxHexes = args.has("maxHexes") ? args.get("maxHexes").asInt() : 1000;

        MapData map = mapService.resolve(worldId, nodeId);
        if (map.provinces().containsKey(name))
            return toJson(Map.of("ok", false, "error", "Region already exists: " + name));

        String seedKey = MapData.hexKey(seedQ, seedR);
        if (!map.hexes().containsKey(seedKey))
            return toJson(Map.of("ok", false, "error", "Seed hex not on map: " + seedKey));

        // Build owned hex set
        Set<String> owned = new HashSet<>();
        for (var p : map.provinces().values()) {
            owned.addAll(p.hexes());
        }
        if (owned.contains(seedKey)) return toJson(Map.of("ok", false, "error", "Seed hex already owned"));

        // ── 1. Flood-fill unowned hexes ──
        Set<String> collected = new LinkedHashSet<>();
        var queue = new ArrayDeque<String>();
        queue.add(seedKey);
        collected.add(seedKey);
        while (!queue.isEmpty() && collected.size() < maxHexes) {
            String cur = queue.poll();
            int[] qr = MapData.parseHexKey(cur);
            for (int[] d : DIRS) {
                String nk = MapData.hexKey(qr[0] + d[0], qr[1] + d[1]);
                if (map.hexes().containsKey(nk) && !owned.contains(nk) && collected.add(nk)) {
                    queue.add(nk);
                }
            }
        }
        if (collected.isEmpty()) return toJson(Map.of("ok", false, "error", "No unowned hexes reachable from seed"));

        // ── 2. Create province ──
        String tag = args.has("tag") ? args.get("tag").asText() : "Nation";
        String color = args.has("color")
                ? args.get("color").asText()
                : String.format("#%06x", RANDOM.nextInt(0xFFFFFF) | 0x404040);
        List<String> hexList = new ArrayList<>(collected);

        Map<String, MapData.Province> updatedProvinces = new LinkedHashMap<>(map.provinces());
        updatedProvinces.put(name, new MapData.Province(hexList, color, tag, ""));
        MapData updated = new MapData(
                map.gridSize(),
                map.hexOrientation(),
                map.hexes(),
                map.terrainBlocks(),
                updatedProvinces,
                map.cities(),
                map.rivers(),
                map.roads(),
                map.terrainTypes(),
                map.compressedRegions(),
                map.pathwayGroups());
        mapService.saveFull(worldId, nodeId, updated);
        mapService.syncToGSimNode(worldId, nodeId);

        // ── 3. Build tags for checkpoint elements ──
        List<String> tags = new ArrayList<>(List.of("Nation", name));
        if (args.has("ruler") && !args.get("ruler").asText().isBlank())
            tags.add(args.get("ruler").asText());
        if (args.has("religion") && !args.get("religion").asText().isBlank())
            tags.add(args.get("religion").asText());

        var cp = mapService.getCheckpointService();
        List<String> created = new ArrayList<>();

        // ── 4. Faction checkpoint ──
        if (args.has("faction") && !args.get("faction").asText().isBlank()) {
            cp.addElement(
                    worldId,
                    nodeId,
                    "factions",
                    name,
                    "text",
                    args.get("faction").asText(),
                    tags);
            created.add("factions:" + name);
        }

        // ── 5. Narrative checkpoint ──
        if (args.has("narrative") && !args.get("narrative").asText().isBlank()) {
            cp.addElement(
                    worldId,
                    nodeId,
                    "narrative",
                    name + "开局",
                    "text",
                    args.get("narrative").asText(),
                    tags);
            created.add("narrative:" + name + "开局");
        }

        // ── 6. Worldview checkpoint ──
        if (args.has("worldview") && !args.get("worldview").asText().isBlank()) {
            cp.addElement(
                    worldId,
                    nodeId,
                    "worldview",
                    name + "世界观",
                    "text",
                    args.get("worldview").asText(),
                    tags);
            created.add("worldview:" + name + "世界观");
        }

        // ── 7. Capital city ──
        if (args.has("capital") && !args.get("capital").asText().isBlank()) {
            String capitalName = args.get("capital").asText();
            String cityValue = "名称: " + capitalName + "\n类型: 首都\n所属: " + name + "\n描述: "
                    + (args.has("faction") ? args.get("faction").asText().split("\n")[0] : "");
            cp.addElement(
                    worldId,
                    nodeId,
                    "map",
                    "City:" + capitalName,
                    "map-city",
                    cityValue,
                    List.of("首都", name, capitalName));
            created.add("map:City:" + capitalName);
        }

        // ── 8. Compute center ──
        int sq = 0;
        int sr = 0;
        for (String hk : hexList) {
            int[] qr = MapData.parseHexKey(hk);
            sq += qr[0];
            sr += qr[1];
        }

        log.info("init_nation '{}': {} hexes, {} checkpoint entries created", name, hexList.size(), created.size());
        return toJson(Map.of(
                "ok",
                true,
                "name",
                name,
                "hexCount",
                hexList.size(),
                "tag",
                tag,
                "color",
                color,
                "center",
                Map.of("q", Math.round((float) sq / hexList.size()), "r", Math.round((float) sr / hexList.size())),
                "checkpointsCreated",
                created));
    }

    private String handleUpdateTerrainType(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : "n0000";
        String key = args.get("key").asText();
        MapData map = mapService.resolve(worldId, nodeId);
        if (!map.terrainTypes().containsKey(key))
            return toJson(Map.of("ok", false, "error", "Terrain type not found: " + key));

        MapData.TerrainType existing = map.terrainTypes().get(key);
        String newName = args.has("name") ? args.get("name").asText() : existing.name();
        String newColor = args.has("color") ? args.get("color").asText() : existing.color();
        int newFood = args.has("food") ? args.get("food").asInt() : existing.food();
        int newGold = args.has("gold") ? args.get("gold").asInt() : existing.gold();
        int newStone = args.has("stone") ? args.get("stone").asInt() : existing.stone();
        int newMoveCost = args.has("moveCost") ? args.get("moveCost").asInt() : existing.moveCost();
        String newDesc = args.has("description") ? args.get("description").asText() : existing.description();

        var updatedTypes = new LinkedHashMap<>(map.terrainTypes());
        updatedTypes.put(
                key, new MapData.TerrainType(newName, newColor, newFood, newGold, newStone, newMoveCost, newDesc));

        MapData updated = new MapData(
                map.gridSize(),
                map.hexOrientation(),
                map.hexes(),
                map.terrainBlocks(),
                map.provinces(),
                map.cities(),
                map.rivers(),
                map.roads(),
                updatedTypes,
                map.compressedRegions(),
                new LinkedHashMap<>());
        mapService.saveFull(worldId, nodeId, updated);
        mapService.syncToGSimNode(worldId, nodeId);

        return toJson(Map.of(
                "ok",
                true,
                "key",
                key,
                "name",
                newName,
                "color",
                newColor,
                "food",
                newFood,
                "gold",
                newGold,
                "stone",
                newStone,
                "moveCost",
                newMoveCost));
    }

    /** Read the active node ID from active.json. */
    private String readActiveNodeId(String worldId) {
        try {
            var file = mapService.getWorldsDir().resolve(worldId).resolve("active.json");
            if (!java.nio.file.Files.exists(file)) return null;
            var node = MAPPER.readTree(file.toFile());
            return node.has("nodeId") ? node.get("nodeId").asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String toJson(Object obj) throws IOException {
        return MAPPER.writeValueAsString(obj);
    }
}

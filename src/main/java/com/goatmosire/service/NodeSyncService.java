package com.goatmosire.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gsim.map.MapData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Syncs GoatMosire map data into GSim node files as Elements in the "map" checkpoint.
 *
 * <p>Each geographic unit gets a unique Element address:
 * <pre>
 *   Region:  n0000:map:{tag}:{name}     → type=map-region
 *   Hex:     n0000:map:{terrain}:{q}_{r} → type=hex-terrain
 *   City:    n0000:map:city:{name}       → type=city
 * </pre>
 *
 * <p>Uses Jackson tree model to preserve unknown fields (e.g., "root") in GSim node JSON.
 */
public class NodeSyncService {

    private static final Logger log = LoggerFactory.getLogger(NodeSyncService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path worldsDir;

    public NodeSyncService(Path worldsDir) {
        this.worldsDir = worldsDir;
    }

    /**
     * Full rebuild: clear the "map" checkpoint, then populate with current MapData.
     */
    public void sync(String worldId, String nodeId, MapData mapData) {
        if (mapData == null) return;

        Path nodeFile = worldsDir.resolve(worldId).resolve("nodes").resolve(nodeId + ".json");
        ObjectNode root;

        try {
            if (Files.exists(nodeFile)) {
                JsonNode existing = MAPPER.readTree(nodeFile.toFile());
                if (existing.isObject()) {
                    root = (ObjectNode) existing;
                } else {
                    root = createMinimalNode(nodeId);
                }
            } else {
                root = createMinimalNode(nodeId);
                Files.createDirectories(nodeFile.getParent());
            }
        } catch (IOException e) {
            log.error("Failed to read node file: {}", nodeFile, e);
            return;
        }

        // Navigate to checkpoints → map
        ObjectNode checkpoints = getOrCreateObject(root, "checkpoints");
        ObjectNode mapCp = getOrCreateObject(checkpoints, "map");

        // Set label/type on the checkpoint (won't overwrite if already present)
        mapCp.put("label", "地图信息");
        mapCp.put("type", "map");

        // Rebuild elements array
        ArrayNode elements = MAPPER.createArrayNode();

        // ── Regions ──────────────────────────────────────────
        if (mapData.provinces() != null) {
            for (var entry : mapData.provinces().entrySet()) {
                String name = entry.getKey();
                MapData.Province prov = entry.getValue();
                String tag = prov.tag() != null && !prov.tag().isBlank() ? prov.tag() : "";
                String key = tag.isEmpty() ? "_untagged:" + name : tag + ":" + name;

                String value = buildRegionValue(name, prov, mapData);
                elements.add(createElement(key, "map-region", value,
                    tag.isEmpty() ? new String[]{name} : new String[]{tag, name}));
            }
        }

        // ── Hexes (only those with descriptions) ─────────────
        if (mapData.hexes() != null) {
            for (var entry : mapData.hexes().entrySet()) {
                MapData.HexCell cell = entry.getValue();
                if (cell.description() == null || cell.description().isBlank()) continue;

                String hexKey = entry.getKey();
                int[] qr = MapData.parseHexKey(hexKey);
                String terrain = cell.terrain() != null ? cell.terrain() : "unknown";
                String key = terrain + ":" + qr[0] + "_" + qr[1];

                String value = buildHexValue(qr[0], qr[1], cell, mapData);
                elements.add(createElement(key, "hex-terrain", value,
                    new String[]{terrain, "q" + qr[0] + "r" + qr[1]}));
            }
        }

        // ── Cities ───────────────────────────────────────────
        if (mapData.cities() != null) {
            for (var entry : mapData.cities().entrySet()) {
                String name = entry.getKey();
                MapData.City city = entry.getValue();
                String key = "city:" + name;

                String value = buildCityValue(name, city);
                elements.add(createElement(key, "city", value, new String[]{"city", name}));
            }
        }

        mapCp.set("elements", elements);

        // Write back
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(nodeFile.toFile(), root);
            log.info("Synced {} elements to {} map checkpoint (node={})",
                elements.size(), worldId, nodeId);
        } catch (IOException e) {
            log.error("Failed to write node file: {}", nodeFile, e);
        }
    }

    // ── Value builders ──────────────────────────────────────

    private static String buildRegionValue(String name, MapData.Province prov, MapData mapData) {
        StringBuilder sb = new StringBuilder();
        sb.append("名称: ").append(name).append("\n");
        if (prov.tag() != null && !prov.tag().isBlank()) {
            sb.append("Tag: ").append(prov.tag()).append("\n");
        }
        sb.append("格数: ").append(prov.hexes() != null ? prov.hexes().size() : 0).append("\n");
        sb.append("颜色: ").append(prov.color()).append("\n");

        // Terrain composition
        if (prov.hexes() != null && mapData.hexes() != null) {
            Map<String, Integer> terrainCounts = new LinkedHashMap<>();
            for (String hk : prov.hexes()) {
                MapData.HexCell cell = mapData.hexes().get(hk);
                if (cell != null) {
                    terrainCounts.merge(cell.terrain(), 1, Integer::sum);
                }
            }
            if (!terrainCounts.isEmpty()) {
                sb.append("地形构成: ");
                terrainCounts.forEach((t, c) -> sb.append(t).append("×").append(c).append(" "));
                sb.append("\n");
            }
        }

        if (prov.description() != null && !prov.description().isBlank()) {
            sb.append("描述: ").append(prov.description());
        }
        return sb.toString().strip();
    }

    private static String buildHexValue(int q, int r, MapData.HexCell cell, MapData mapData) {
        StringBuilder sb = new StringBuilder();
        sb.append("坐标: (").append(q).append(", ").append(r).append(")\n");
        sb.append("地形: ").append(cell.terrain()).append("\n");

        MapData.TerrainType tt = mapData.terrainTypes() != null
            ? mapData.terrainTypes().get(cell.terrain()) : null;
        if (tt != null) {
            sb.append("产出: 🍖").append(tt.food())
              .append(" 💰").append(tt.gold())
              .append(" 🪨").append(tt.stone())
              .append(" 👣").append(tt.moveCost()).append("\n");
        }
        sb.append("描述: ").append(cell.description());
        return sb.toString();
    }

    private static String buildCityValue(String name, MapData.City city) {
        StringBuilder sb = new StringBuilder();
        sb.append("名称: ").append(name).append("\n");
        sb.append("坐标: (").append(city.q()).append(", ").append(city.r()).append(")\n");
        if (city.description() != null && !city.description().isBlank()) {
            sb.append("描述: ").append(city.description());
        }
        return sb.toString().strip();
    }

    // ── JSON helpers ────────────────────────────────────────

    private static ObjectNode getOrCreateObject(ObjectNode parent, String field) {
        JsonNode existing = parent.get(field);
        if (existing != null && existing.isObject()) {
            return (ObjectNode) existing;
        }
        ObjectNode created = MAPPER.createObjectNode();
        parent.set(field, created);
        return created;
    }

    private static ObjectNode createElement(String key, String type, String value, String[] tags) {
        ObjectNode el = MAPPER.createObjectNode();
        el.put("key", key);
        el.put("type", type);
        el.put("value", value);

        ArrayNode tagArr = MAPPER.createArrayNode();
        for (String t : tags) tagArr.add(t);
        el.set("tags", tagArr);

        el.set("links", MAPPER.createArrayNode());

        String now = java.time.Instant.now().toString();
        el.put("createdAt", now);
        el.put("updatedAt", now);
        return el;
    }

    private static ObjectNode createMinimalNode(String nodeId) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("nodeId", nodeId);
        root.put("parentId", (String) null);
        root.put("turn", 0);
        root.put("worldTime", "");
        root.put("status", "initial");
        root.put("createdAt", java.time.Instant.now().toString());
        root.set("checkpoints", MAPPER.createObjectNode());
        root.put("root", false);
        return root;
    }
}

package com.goatmosire.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes map files in the GSim worlds directory structure.
 *
 * <p>File layout:
 * <pre>
 *   worlds/{worldId}/nodes/{nodeId}_map.json   ← map data for a specific node
 * </pre>
 *
 * <p>Root node (n0000) stores a full {@link MapData}.
 * Child nodes store a {@link MapDiff} (optional — if absent, inherits parent unchanged).
 */
public final class MapStore {

    private static final Logger log = LoggerFactory.getLogger(MapStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private MapStore() {}

    // ── Path helpers ─────────────────────────────────────

    /**
     * worlds/{worldId}/nodes/{nodeId}_map.json
     *
     * @param worldsDir the worlds root directory
     * @param worldId   the world identifier
     * @param nodeId    the node identifier
     * @return the path to the map file
     */
    public static Path mapFile(Path worldsDir, String worldId, String nodeId) {
        return worldsDir.resolve(worldId).resolve("nodes").resolve(nodeId + "_map.json");
    }

    /**
     * Check if a map file exists for the given node.
     *
     * @param worldsDir the worlds root directory
     * @param worldId   the world identifier
     * @param nodeId    the node identifier
     * @return true if the map file exists
     */
    public static boolean exists(Path worldsDir, String worldId, String nodeId) {
        return Files.exists(mapFile(worldsDir, worldId, nodeId));
    }

    // ── Full map (root nodes) ────────────────────────────

    /**
     * Load a full MapData from a node's _map.json.
     *
     * @param worldsDir the worlds root directory
     * @param worldId   the world identifier
     * @param nodeId    the node identifier
     * @return the loaded MapData, or null if absent
     */
    public static MapData loadFull(Path worldsDir, String worldId, String nodeId) {
        Path file = mapFile(worldsDir, worldId, nodeId);
        if (!Files.exists(file)) return null;
        try {
            MapData data = MAPPER.readValue(file.toFile(), MapData.class);
            if (data == null) return null;
            return data;
        } catch (IOException e) {
            log.error("Failed to load map: {}", file, e);
            return null;
        }
    }

    /**
     * Save a full MapData to a node's _map.json.
     *
     * @param worldsDir the worlds root directory
     * @param worldId   the world identifier
     * @param nodeId    the node identifier
     * @param data      the map data to save (must not be null)
     * @throws MapStoreException if data is null or an I/O error occurs
     */
    public static void saveFull(Path worldsDir, String worldId, String nodeId, MapData data) {
        if (data == null) throw new MapStoreException("data must not be null");
        Path file = mapFile(worldsDir, worldId, nodeId);
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            MAPPER.writeValue(file.toFile(), data);
            log.debug("Saved full map: {}", file);
        } catch (IOException e) {
            log.error("Failed to save map: {}", file, e);
            throw new MapStoreException("Failed to save map: " + file, e);
        }
    }

    // ── Diff map (child nodes) ───────────────────────────

    /**
     * Load a MapDiff from a node's _map.json.
     *
     * @param worldsDir the worlds root directory
     * @param worldId   the world identifier
     * @param nodeId    the node identifier
     * @return the loaded MapDiff, or null if absent or if it's a full map
     */
    public static MapDiff loadDiff(Path worldsDir, String worldId, String nodeId) {
        Path file = mapFile(worldsDir, worldId, nodeId);
        if (!Files.exists(file)) return null;
        try {
            // Try parsing as MapDiff first (has parentNodeId field)
            var node = MAPPER.readTree(file.toFile());
            if (node == null || !node.has("parentNodeId")) return null;
            return MAPPER.treeToValue(node, MapDiff.class);
        } catch (IOException e) {
            log.error("Failed to load map diff: {}", file, e);
            return null;
        }
    }

    /**
     * Save a MapDiff to a node's _map.json.
     *
     * @param worldsDir the worlds root directory
     * @param worldId   the world identifier
     * @param nodeId    the node identifier
     * @param diff      the map diff to save (must not be null)
     * @throws MapStoreException if diff is null or an I/O error occurs
     */
    public static void saveDiff(Path worldsDir, String worldId, String nodeId, MapDiff diff) {
        if (diff == null) throw new MapStoreException("diff must not be null");
        Path file = mapFile(worldsDir, worldId, nodeId);
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            MAPPER.writeValue(file.toFile(), diff);
            log.debug("Saved map diff: {}", file);
        } catch (IOException e) {
            log.error("Failed to save map diff: {}", file, e);
            throw new MapStoreException("Failed to save map diff: " + file, e);
        }
    }
}

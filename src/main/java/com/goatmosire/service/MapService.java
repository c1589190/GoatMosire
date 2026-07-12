package com.goatmosire.service;

import com.gsim.map.*;
import com.goatmosire.config.GoatMosireConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core map service — resolves map data from the GSim worlds directory,
 * applies diffs, manages an in-memory LRU cache.
 */
public class MapService {

    private static final Logger log = LoggerFactory.getLogger(MapService.class);
    private static final int MAX_CACHE_SIZE = 32;

    private final Path worldsDir;
    private final Map<String, MapData> cache = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, MapData> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    public MapService(Path worldsDir) {
        this.worldsDir = worldsDir;
        if (!Files.isDirectory(worldsDir)) {
            log.warn("Worlds directory does not exist: {}", worldsDir);
        }
    }

    // ── Query ────────────────────────────────────────────

    /** Resolve the full map for a given world + node. */
    public MapData resolve(String worldId, String nodeId) {
        String key = cacheKey(worldId, nodeId);
        return cache.computeIfAbsent(key, k -> {
            log.debug("Cache miss for {}, resolving...", k);
            return MapResolver.resolve(worldsDir, worldId, nodeId);
        });
    }

    /** Get map for the active node of a world. */
    public MapData resolveActive(String worldId) {
        String nodeId = readActiveNodeId(worldId);
        if (nodeId == null) return MapData.empty();
        return resolve(worldId, nodeId);
    }

    /** Get history for a world + node. */
    public List<MapResolver.HistoryEntry> history(String worldId, String nodeId) {
        return MapResolver.history(worldsDir, worldId, nodeId);
    }

    // ── Mutation ──────────────────────────────────────────

    /** Save a full map (for root nodes or initial setup). */
    public void saveFull(String worldId, String nodeId, MapData data) {
        MapStore.saveFull(worldsDir, worldId, nodeId, data);
        evict(worldId, nodeId);
    }

    /** Save a diff (for child nodes). */
    public void saveDiff(String worldId, String nodeId, MapDiff diff) {
        MapStore.saveDiff(worldsDir, worldId, nodeId, diff);
        evict(worldId, nodeId);
    }

    /** Check if a world exists. */
    public boolean worldExists(String worldId) {
        return Files.exists(worldsDir.resolve(worldId).resolve("world.json"));
    }

    /** List worlds that have map data. */
    public List<String> listWorldsWithMaps() {
        List<String> result = new ArrayList<>();
        java.io.File[] worldDirs = worldsDir.toFile().listFiles(java.io.File::isDirectory);
        if (worldDirs == null) return result;
        for (java.io.File wf : worldDirs) {
            Path w = wf.toPath();
            Path nodesDir = w.resolve("nodes");
            if (!Files.isDirectory(nodesDir)) continue;
            // Check if any node has a _map.json
            try (var stream = Files.list(nodesDir)) {
                if (stream.anyMatch(f -> f.getFileName().toString().endsWith("_map.json"))) {
                    result.add(w.getFileName().toString());
                }
            } catch (Exception ignored) {}
        }
        return result;
    }

    // ── Cache ─────────────────────────────────────────────

    public void evict(String worldId, String nodeId) {
        String key = cacheKey(worldId, nodeId);
        cache.remove(key);
        // Also evict any descendant nodes' cached resolved map (they inherit from this)
        String prefix = worldId + "/";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private static String cacheKey(String worldId, String nodeId) {
        return worldId + "/" + nodeId;
    }

    // ── Helpers ───────────────────────────────────────────

    private String readActiveNodeId(String worldId) {
        Path activeFile = worldsDir.resolve(worldId).resolve("active.json");
        if (!Files.exists(activeFile)) return null;
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(activeFile.toFile());
            if (node.has("nodeId")) return node.get("nodeId").asText();
        } catch (Exception e) {
            log.warn("Failed to read active.json for world {}", worldId, e);
        }
        return null;
    }
}

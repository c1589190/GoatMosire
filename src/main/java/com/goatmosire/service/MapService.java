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

    // ── Nodes ──────────────────────────────────────────────

    /** List all nodes in a world directory. Returns nodeId + turn if the node JSON is readable. */
    public List<Map<String, Object>> listNodes(String worldId) {
        List<Map<String, Object>> result = new ArrayList<>();
        Path nodesDir = worldsDir.resolve(worldId).resolve("nodes");
        if (!Files.isDirectory(nodesDir)) return result;

        try (var stream = Files.list(nodesDir)) {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            stream.filter(f -> {
                String name = f.getFileName().toString();
                return name.startsWith("n") && name.endsWith(".json") && !name.contains("_map");
            }).sorted().forEach(f -> {
                try {
                    var node = mapper.readTree(f.toFile());
                    Map<String, Object> info = new LinkedHashMap<>();
                    String nid = f.getFileName().toString().replace(".json", "");
                    info.put("nodeId", nid);
                    info.put("turn", node.has("turn") ? node.get("turn").asInt() : -1);
                    info.put("worldTime", node.has("worldTime") ? node.get("worldTime").asText() : "");
                    info.put("hasMap", Files.exists(
                        worldsDir.resolve(worldId).resolve("nodes").resolve(nid + "_map.json")));
                    result.add(info);
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
        return result;
    }

    // ── Pathfinding ────────────────────────────────────────

    /**
     * Find the minimum-cost path from a source hex to the nearest water hex (or map edge).
     * Uses Dijkstra with terrain moveCost as edge weight.
     * Falls back to A* with hex-distance heuristic to water.
     */
    public List<String> findRiverPath(String worldId, String nodeId, int fromQ, int fromR) {
        MapData map = resolve(worldId, nodeId != null ? nodeId : "n0000");
        String startKey = MapData.hexKey(fromQ, fromR);

        // Priority queue: [fCost, gCost, q, r]
        var pq = new java.util.PriorityQueue<int[]>(
            java.util.Comparator.comparingInt(a -> a[0]));
        var cameFrom = new java.util.HashMap<String, String>();
        var gCost = new java.util.HashMap<String, Integer>();
        gCost.put(startKey, 0);

        // Heuristic: hex distance to nearest water
        // Use 0 for pure Dijkstra (more reliable for small maps)
        int h = 0; // estimateWaterDistance(map, fromQ, fromR);
        pq.add(new int[]{h, 0, fromQ, fromR});

        int[][] dirs = {{1,0},{1,-1},{0,-1},{-1,0},{-1,1},{0,1}};
        String targetKey = null;
        int iter = 0;
        int maxIter = 5000;

        while (!pq.isEmpty() && iter++ < maxIter) {
            int[] cur = pq.poll();
            int f = cur[0], g = cur[1], q = cur[2], r = cur[3];
            String curKey = MapData.hexKey(q, r);

            if (g > gCost.getOrDefault(curKey, Integer.MAX_VALUE)) continue;

            // Check if this is water (goal)
            MapData.HexCell cell = map.hexes().get(curKey);
            if (cell != null && "water".equals(cell.terrain())) {
                targetKey = curKey; break;
            }

            for (int[] d : dirs) {
                int nq = q + d[0], nr = r + d[1];
                String nk = MapData.hexKey(nq, nr);
                MapData.HexCell nc = map.hexes().get(nk);
                int moveCost = nc != null
                    ? map.terrainTypes().containsKey(nc.terrain())
                        ? map.terrainTypes().get(nc.terrain()).moveCost()
                        : 2
                    : 1; // empty = easy
                int ng = g + moveCost;
                if (ng < gCost.getOrDefault(nk, Integer.MAX_VALUE)) {
                    gCost.put(nk, ng);
                    cameFrom.put(nk, curKey);
                    int nh = 0; // estimateWaterDistance(map, nq, nr);
                    pq.add(new int[]{ng + nh, ng, nq, nr});
                }
            }
        }

        if (targetKey == null) return List.of();

        // Reconstruct path
        var path = new java.util.ArrayList<String>();
        String k = targetKey;
        while (k != null) {
            path.add(k);
            k = cameFrom.get(k);
        }
        java.util.Collections.reverse(path);
        return path;
    }

    private int estimateWaterDistance(MapData map, int q, int r) {
        // Simple heuristic: find the nearest water hex using spiral search (capped)
        int maxR = 20;
        int[][] dirs = {{1,0},{1,-1},{0,-1},{-1,0},{-1,1},{0,1}};
        for (int radius = 1; radius <= maxR; radius++) {
            int cq = q, cr = r;
            // Start at direction 4 (NW), move radius steps
            for (int d = 0; d < 6; d++) {
                for (int step = 0; step < radius; step++) {
                    cq += dirs[d][0]; cr += dirs[d][1];
                    String key = MapData.hexKey(cq, cr);
                    MapData.HexCell cell = map.hexes().get(key);
                    if (cell == null) return radius;       // map edge = water-ish
                    if ("water".equals(cell.terrain())) return radius;
                }
            }
        }
        return maxR;
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

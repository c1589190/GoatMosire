package com.goatmosire.service;

import com.gsim.map.*;
import com.goatmosire.config.GoatMosireConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
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
    private final ConcurrentHashMap<String, TerrainCanvas> canvases = new ConcurrentHashMap<>();
    private final NodeSyncService nodeSyncService;

    public MapService(Path worldsDir) {
        this.worldsDir = worldsDir;
        this.nodeSyncService = new NodeSyncService(worldsDir);
        if (!Files.isDirectory(worldsDir)) {
            log.warn("Worlds directory does not exist: {}", worldsDir);
        }
    }

    public Path getWorldsDir() { return worldsDir; }

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

    // ── TerrainCanvas (block system) ────────────────────────

    /** Get or lazily create the TerrainCanvas for a world. */
    public TerrainCanvas getCanvas(String worldId) {
        return canvases.computeIfAbsent(worldId, k -> {
            // Try to load existing blocks from the stored map
            MapData map = resolveActive(worldId);
            TerrainCanvas canvas = new TerrainCanvas();
            if (map != null && map.terrainBlocks() != null && !map.terrainBlocks().isEmpty()) {
                canvas.setBlocks(map.terrainBlocks());
                log.info("Loaded {} blocks for world {}", canvas.size(), worldId);
            } else {
                log.info("Empty canvas for world {}", worldId);
            }
            return canvas;
        });
    }

    /** Query terrain for a hex using TerrainCanvas (primary), falling back to hexes. */
    public String queryTerrainBlock(String worldId, int q, int r) {
        TerrainCanvas canvas = canvases.get(worldId);
        if (canvas != null) {
            String terrain = canvas.queryHex(q, r);
            if (terrain != null) return terrain;
        }
        // Fallback 1: load stored terrainBlocks into canvas and query
        MapData map = resolveActive(worldId);
        if (map != null && map.terrainBlocks() != null && !map.terrainBlocks().isEmpty()) {
            canvas = new TerrainCanvas();
            canvas.setBlocks(map.terrainBlocks());
            canvases.put(worldId, canvas);
            String terrain = canvas.queryHex(q, r);
            if (terrain != null) return terrain;
        }
        // Fallback 2: query hex grid
        if (map != null) {
            MapData.HexCell cell = map.hexes().get(MapData.hexKey(q, r));
            if (cell != null) return cell.terrain();
        }
        return null;
    }

    /** Add a terrain block to a world's canvas and persist. */
    public String addBlock(String worldId, String terrain, List<MapData.Pt> boundary, String seedKey) {
        TerrainCanvas canvas = getCanvas(worldId);
        String blockId = canvas.addBlock(terrain, boundary, seedKey);
        if (blockId != null) {
            persistBlocks(worldId, canvas);
        }
        return blockId;
    }

    /** Add a block from pre-computed hex set (client-side flood fill). */
    public String addBlockFromHexSet(String worldId, String terrain, Set<String> hexSet, String seedKey) {
        TerrainCanvas canvas = getCanvas(worldId);
        String blockId = canvas.addBlockFromHexSet(terrain, hexSet, seedKey);
        if (blockId != null) {
            persistBlocks(worldId, canvas);
        }
        return blockId;
    }

    /** Remove a terrain block by id and persist. */
    public boolean removeBlock(String worldId, String blockId) {
        TerrainCanvas canvas = canvases.get(worldId);
        if (canvas == null) return false;
        boolean ok = canvas.removeBlock(blockId);
        if (ok) persistBlocks(worldId, canvas);
        return ok;
    }

    public void evictCanvas(String worldId) {
        canvases.remove(worldId);
    }

    /** Write terrain blocks back to MapData and persist (does NOT evict canvas). */
    private void persistBlocks(String worldId, TerrainCanvas canvas) {
        List<MapData.TerrainBlock> blocks = canvas.getBlocks();
        MapData map = resolveActive(worldId);
        if (map == null || map.hexes().isEmpty()) {
            map = MapData.empty();
        }
        MapData updated = new MapData(
            map.gridSize(), map.hexOrientation(), map.hexes(),
            blocks, map.provinces(), map.cities(),
            map.rivers(), map.roads(), map.terrainTypes()
        );
        // Save directly to disk without evicting the canvas cache
        MapStore.saveFull(worldsDir, worldId, "n0000", updated);
        // Update the in-memory MapData cache
        cache.put(cacheKey(worldId, "n0000"), updated);
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
        log.info("findRiverPath: world={} node={} start={} hexes={}", worldId, nodeId, startKey, map.hexes().size());

        // Priority queue: [fCost, gCost, q, r]
        var pq = new java.util.PriorityQueue<int[]>(
            java.util.Comparator.comparingInt(a -> a[0]));
        var cameFrom = new java.util.HashMap<String, String>();
        var gCost = new java.util.HashMap<String, Integer>();
        gCost.put(startKey, 0);

        // A* heuristic: estimated distance to nearest water
        int h = estimateWaterDistance(map, fromQ, fromR);
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
                if (nc == null) continue;  // don't expand into void
                int moveCost = map.terrainTypes().containsKey(nc.terrain())
                    ? map.terrainTypes().get(nc.terrain()).moveCost()
                    : 2;
                int ng = g + moveCost;
                if (ng < gCost.getOrDefault(nk, Integer.MAX_VALUE)) {
                    gCost.put(nk, ng);
                    cameFrom.put(nk, curKey);
                    int nh = estimateWaterDistance(map, nq, nr);
                    pq.add(new int[]{ng + nh, ng, nq, nr});
                }
            }
        }

        if (targetKey == null) {
            log.warn("findRiverPath: no path found from {}", startKey);
            return List.of();
        }

        log.info("findRiverPath: found target={} after {} iters", targetKey, iter);

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
        // Spiral ring search for nearest water hex (capped at 20)
        int maxR = 20;
        int[][] dirs = {{1,0},{1,-1},{0,-1},{-1,0},{-1,1},{0,1}};
        for (int radius = 1; radius <= maxR; radius++) {
            // Start at NW corner of the ring (direction 4)
            int cq = q + dirs[4][0] * radius;
            int cr = r + dirs[4][1] * radius;
            for (int d = 0; d < 6; d++) {
                for (int step = 0; step < radius; step++) {
                    String key = MapData.hexKey(cq, cr);
                    MapData.HexCell cell = map.hexes().get(key);
                    if (cell == null) return radius;
                    if ("water".equals(cell.terrain())) return radius;
                    cq += dirs[d][0]; cr += dirs[d][1];
                }
            }
        }
        return maxR;
    }

    // ── Contour ────────────────────────────────────────────

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Save continent contour for a world */
    public void saveContour(String worldId, ContinentContour contour) {
        try {
            Path dir = worldsDir.resolve(worldId).resolve("nodes");
            Files.createDirectories(dir);
            Path file = dir.resolve("contour.json");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), contour);
            evict(worldId, "n0000");
        } catch (IOException e) {
            log.error("Failed to save contour for world {}", worldId, e);
        }
    }

    /** Load continent contour for a world */
    public ContinentContour loadContour(String worldId) {
        try {
            Path file = worldsDir.resolve(worldId).resolve("nodes").resolve("contour.json");
            if (!Files.exists(file)) return null;
            return MAPPER.readValue(file.toFile(), ContinentContour.class);
        } catch (IOException e) {
            log.error("Failed to load contour for world {}", worldId, e);
            return null;
        }
    }

    /** Query terrain for a single hex using contour (lazy, cached) */
    public ContourQueryEngine.TerrainSample queryTerrain(String worldId, int q, int r) {
        ContinentContour contour = loadContour(worldId);
        if (contour == null) {
            // Fallback: resolve full map and query traditionally
            MapData map = resolve(worldId, null);
            MapData.HexCell cell = map.hexes().get(q + "_" + r);
            if (cell == null) return new ContourQueryEngine.TerrainSample(0, "water", "#3295D2");
            return new ContourQueryEngine.TerrainSample(0.5, cell.terrain(), cell.color());
        }
        ContourQueryEngine engine = new ContourQueryEngine(contour);
        return engine.query(q, r);
    }

    // ── Cache ─────────────────────────────────────────────

    // ── GSim Node Sync ────────────────────────────────────

    /** Sync map data (regions, described hexes, cities) into the GSim node's "map" checkpoint. */
    public void syncToGSimNode(String worldId, String nodeId) {
        MapData map = resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty()) return;
        nodeSyncService.sync(worldId, nodeId, map);
    }

    public void evict(String worldId, String nodeId) {
        String key = cacheKey(worldId, nodeId);
        cache.remove(key);
        // Also evict any descendant nodes' cached resolved map (they inherit from this)
        String prefix = worldId + "/";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
        canvases.remove(worldId);
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

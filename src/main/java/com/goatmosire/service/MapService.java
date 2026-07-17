package com.goatmosire.service;

import com.gsim.map.*;
import com.goatmosire.config.GoatMosireConfig;
import com.fasterxml.jackson.databind.JsonNode;
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
    private final Map<String, MapData> cache = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, MapData> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    });
    private final ConcurrentHashMap<String, TerrainCanvas> canvases = new ConcurrentHashMap<>();
    private final NodeSyncService nodeSyncService;
    private final CheckpointService checkpointService;

    public MapService(Path worldsDir) {
        this.worldsDir = worldsDir;
        this.nodeSyncService = new NodeSyncService(worldsDir);
        this.checkpointService = new CheckpointService(worldsDir);
        if (!Files.isDirectory(worldsDir)) {
            log.warn("Worlds directory does not exist: {}", worldsDir);
        }
    }

    public Path getWorldsDir() { return worldsDir; }
    public CheckpointService getCheckpointService() { return checkpointService; }

    // ── Query ────────────────────────────────────────────

    /** Resolve the full map for a given world + node. */
    public MapData resolve(String worldId, String nodeId) {
        String key = cacheKey(worldId, nodeId);
        return cache.computeIfAbsent(key, k -> loadMapData(worldId, nodeId));
    }

    /** Load MapData. compressedRegions is now a native field — no stripping needed. */
    private MapData loadMapData(String worldId, String nodeId) {
        if (isRootNode(worldId, nodeId)) {
            return MapStore.loadFull(worldsDir, worldId, nodeId);
        }
        // Child node: use gsim-core MapResolver for diff chain
        return MapResolver.resolve(worldsDir, worldId, nodeId);
    }

    private boolean isRootNode(String worldId, String nodeId) {
        Path nodeFile = worldsDir.resolve(worldId).resolve("nodes").resolve(nodeId + ".json");
        if (!Files.exists(nodeFile)) return true;
        try {
            var node = MAPPER.readTree(nodeFile.toFile());
            if (node.has("parentId") && !node.get("parentId").isNull()) {
                String pid = node.get("parentId").asText();
                return pid.isBlank();
            }
        } catch (Exception e) { /* fall through */ }
        return true;
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
            map.rivers(), map.roads(), map.terrainTypes(), map.compressedRegions()
        );
        // Save directly to disk without evicting the canvas cache
        MapStore.saveFull(worldsDir, worldId, "n0000", updated);
        // Update the in-memory MapData cache
        cache.put(cacheKey(worldId, "n0000"), updated);
    }

    // ── Mutation ──────────────────────────────────────────

    /** Save a full map. compressedRegions is a native MapData field — auto-serialized. */
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
                return name.startsWith("n") && name.endsWith(".json") && !name.contains("_map") && !name.contains("_compressed");
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

    // ── Region Rename ──────────────────────────────────────

    /** Rename a region across all data stores: MapData + GSim node checkpoints. */
    public Map<String, Object> renameRegion(String worldId, String nodeId, String oldName, String newName) {
        if (oldName == null || newName == null || oldName.equals(newName))
            return Map.of("ok", false, "error", "Invalid names");
        if (newName.isBlank())
            return Map.of("ok", false, "error", "New name must not be blank");

        MapData map = resolve(worldId, nodeId);
        if (map == null || !map.provinces().containsKey(oldName))
            return Map.of("ok", false, "error", "Region not found: " + oldName);
        if (map.provinces().containsKey(newName))
            return Map.of("ok", false, "error", "Region already exists: " + newName);

        // 1. Rename province in MapData
        Map<String, MapData.Province> updated = new LinkedHashMap<>();
        for (var e : map.provinces().entrySet()) {
            if (e.getKey().equals(oldName)) updated.put(newName, e.getValue());
            else updated.put(e.getKey(), e.getValue());
        }
        MapData newMap = new MapData(map.gridSize(), map.hexOrientation(), map.hexes(),
            map.terrainBlocks(), updated, map.cities(),
            map.rivers(), map.roads(), map.terrainTypes(), map.compressedRegions());
        saveFull(worldId, nodeId, newMap);

        // 2. Update checkpoint references in node JSON
        try {
            checkpointService.renameReferences(worldId, nodeId, oldName, newName);
        } catch (Exception ex) {
            log.warn("Checkpoint rename partially failed: {}", ex.getMessage());
        }

        // 3. Re-sync map checkpoint with new name
        evict(worldId, nodeId);  // ensure HTTP cache sees MCP writes
        syncToGSimNode(worldId, nodeId);

        log.info("Renamed region '{}' -> '{}' in world={} node={}", oldName, newName, worldId, nodeId);
        return Map.of("ok", true, "oldName", oldName, "newName", newName);
    }

    // ── GSim Node Sync ────────────────────────────────────

    /** Sync map data (regions, described hexes, cities) into the GSim node's "map" checkpoint. */
    public void syncToGSimNode(String worldId, String nodeId) {
        MapData map = resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty()) return;
        nodeSyncService.sync(worldId, nodeId, map);
    }

    // ── Map Expansion ──────────────────────────────────────

    private static final int[][] EXPAND_DIRS = {{1,0},{1,-1},{0,-1},{-1,0},{-1,1},{0,1}};
    private static final String[] EXPAND_NAMES = {"E","NE","NW","W","SW","SE"};

    /** Expand the map by attaching a same-size hexagon in the given direction,
     *  then filling diamond-feet gaps to form a larger coherent hexagon. */
    public Map<String, Object> expand(String worldId, String nodeId, String direction, int attachRadius) {
        MapData map = resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty())
            return Map.of("ok", false, "error", "No map data");

        // Find direction index
        int dirIdx = -1;
        for (int i = 0; i < EXPAND_NAMES.length; i++) {
            if (EXPAND_NAMES[i].equals(direction)) { dirIdx = i; break; }
        }
        if (dirIdx < 0)
            return Map.of("ok", false, "error", "Invalid direction: " + direction
                + ". Use: E, NE, NW, W, SW, SE");

        // Compute current center and radius from hex data
        int minQ = Integer.MAX_VALUE, maxQ = Integer.MIN_VALUE;
        int minR = Integer.MAX_VALUE, maxR = Integer.MIN_VALUE;
        for (String key : map.hexes().keySet()) {
            int[] qr = MapData.parseHexKey(key);
            if (qr[0] < minQ) minQ = qr[0]; if (qr[0] > maxQ) maxQ = qr[0];
            if (qr[1] < minR) minR = qr[1]; if (qr[1] > maxR) maxR = qr[1];
        }
        int cq = (minQ + maxQ) / 2;
        int cr = (minR + maxR) / 2;
        int cs = -cq - cr;
        int radius = 0;
        for (String key : map.hexes().keySet()) {
            int[] qr = MapData.parseHexKey(key);
            int s = -qr[0] - qr[1];
            radius = Math.max(radius, Math.abs(qr[0]-cq) + Math.abs(qr[1]-cr) + Math.abs(s-cs));
        }
        radius = (radius + 1) / 2;
        int useRadius = attachRadius > 0 ? attachRadius : radius;

        int[] dir = EXPAND_DIRS[dirIdx];
        int[] perp = EXPAND_DIRS[(dirIdx + 2) % 6];  // 60°×2 ≈ perpendicular
        int dq = dir[0], dr = dir[1];
        int pq = perp[0], pr = perp[1];

        // H2 center and combined hexagon
        int h2_q = 2 * useRadius * dq + useRadius * pq;
        int h2_r = 2 * useRadius * dr + useRadius * pr;
        int newCq = useRadius * (dq + pq);
        int newCr = useRadius * (dr + pr);
        int newRadius = 2 * useRadius;
        int newCs = -newCq - newCr;

        // Load contour for terrain generation
        ContinentContour contour = loadContour(worldId);
        ContourQueryEngine engine = contour != null ? new ContourQueryEngine(contour) : null;

        // Build expanded hex grid
        var newHexes = new LinkedHashMap<>(map.hexes());
        int added = 0, waterAdded = 0, landAdded = 0;

        for (int q = newCq - newRadius; q <= newCq + newRadius; q++) {
            for (int r = newCr - newRadius; r <= newCr + newRadius; r++) {
                String key = MapData.hexKey(q, r);
                if (newHexes.containsKey(key)) continue;
                int s = -q - r;
                if (Math.abs(q-newCq) + Math.abs(r-newCr) + Math.abs(s-newCs) > 2*newRadius) continue;

                String terrain, color;
                if (engine != null) {
                    var sample = engine.query(q, r);
                    terrain = sample.terrain();
                    color = sample.color();
                } else {
                    terrain = "lowland";
                    color = "#5B8C3E";
                }
                int riverMask = 0;
                newHexes.put(key, new MapData.HexCell(color, terrain, null, null, "", riverMask));
                added++;
                if ("water".equals(terrain)) waterAdded++;
                else landAdded++;
            }
        }

        int hexesBefore = map.hexes().size();
        MapData expanded = new MapData(map.gridSize(), map.hexOrientation(), newHexes,
            map.terrainBlocks(), map.provinces(), map.cities(),
            map.rivers(), map.roads(), map.terrainTypes(), map.compressedRegions());
        saveFull(worldId, nodeId, expanded);

        log.info("Expanded {} → {} ({} new hexes: {} land + {} water), new center=({},{}), radius={}",
            direction, worldId, added, landAdded, waterAdded, newCq, newCr, newRadius);

        var result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("direction", direction);
        result.put("hexesBefore", hexesBefore);
        result.put("hexesAfter", newHexes.size());
        result.put("added", added);
        result.put("landAdded", landAdded);
        result.put("waterAdded", waterAdded);
        result.put("oldCenter", Map.of("q", cq, "r", cr));
        result.put("oldRadius", useRadius);
        result.put("newCenter", Map.of("q", newCq, "r", newCr));
        result.put("newRadius", newRadius);
        return result;
    }

    // ── Compression ───────────────────────────────────────

    /** Compress large same-terrain regions in the resolved map and store per-node. */
    public Map<String, Object> compress(String worldId, String nodeId, int minRegionSize) {
        MapData map = resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty())
            return Map.of("ok", false, "error", "No map data");

        if (minRegionSize <= 0) minRegionSize = CompressionService.DEFAULT_MIN_REGION_SIZE;

        List<MapData.CompressedRegion> regions = CompressionService.compress(map, minRegionSize);
        saveCr(worldId, nodeId, regions);

        log.info("Compressed {}/{}: {} hexes, {} regions", worldId, nodeId, map.hexes().size(), regions.size());

        var result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("nodeId", nodeId);
        result.put("hexCount", map.hexes().size());
        result.put("regions", regions.size());
        int compressedHexes = regions.stream().mapToInt(MapData.CompressedRegion::size).sum();
        result.put("compressedCount", compressedHexes);
        result.put("compressionRatio", map.hexes().size() > 0
            ? String.format("%.1f%%", 100.0 * compressedHexes / map.hexes().size()) : "0%");
        return result;
    }

    /** Decompress a specific region by id (remove from per-node CR file). */
    public Map<String, Object> decompress(String worldId, String nodeId, String regionId) {
        List<MapData.CompressedRegion> regions = loadCr(worldId, nodeId);
        if (regions.isEmpty())
            return Map.of("ok", false, "error", "No compressed regions for node: " + nodeId);

        List<MapData.CompressedRegion> mutable = new ArrayList<>(regions);
        int restored = CompressionService.decompress(mutable, regionId);
        if (restored == 0)
            return Map.of("ok", false, "error", "Region not found: " + regionId);

        saveCr(worldId, nodeId, mutable);
        return Map.of("ok", true, "restored", restored, "regionsRemaining", mutable.size());
    }

    /** Decompress the region covering hex (q, r) — reads from per-node CR. */
    public Map<String, Object> decompressAt(String worldId, String nodeId, int q, int r) {
        List<MapData.CompressedRegion> regions = loadCr(worldId, nodeId);
        if (regions.isEmpty())
            return Map.of("ok", true, "note", "no compressed regions", "q", q, "r", r);

        List<MapData.CompressedRegion> mutable = new ArrayList<>(regions);
        int restored = CompressionService.decompressAt(mutable, q, r);
        if (restored == 0)
            return Map.of("ok", true, "note", "hex not in any compressed region", "q", q, "r", r);

        saveCr(worldId, nodeId, mutable);
        return Map.of("ok", true, "restored", restored, "regionsRemaining", mutable.size(), "q", q, "r", r);
    }

    // ── Per-node CR file I/O ───────────────────────────────

    private Path crPath(String worldId, String nodeId) {
        return worldsDir.resolve(worldId).resolve("nodes").resolve(nodeId + "_cr.json");
    }

    private void saveCr(String worldId, String nodeId, List<MapData.CompressedRegion> regions) {
        try {
            Path path = crPath(worldId, nodeId);
            Files.createDirectories(path.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), regions);
            log.info("Saved {} CRs to {}", regions.size(), path);
        } catch (IOException e) {
            log.error("Failed to save CRs for {}/{}", worldId, nodeId, e);
        }
    }

    /**
     * Load compressed regions for a node. Walks parent chain if this node has none.
     * Returns the first CR set found, or empty list.
     */
    public List<MapData.CompressedRegion> loadCr(String worldId, String nodeId) {
        // Try this node first
        Path path = crPath(worldId, nodeId);
        if (Files.exists(path)) {
            try {
                JsonNode arr = MAPPER.readTree(path.toFile());
                if (arr.isArray()) {
                    List<MapData.CompressedRegion> regions = new ArrayList<>();
                    for (JsonNode n : arr) {
                        regions.add(MAPPER.treeToValue(n, MapData.CompressedRegion.class));
                    }
                    return regions;
                }
            } catch (Exception e) {
                log.warn("Failed to load CRs for {}/{}", worldId, nodeId, e);
            }
        }

        // Fall back to parent
        try {
            Path nodeFile = worldsDir.resolve(worldId).resolve("nodes").resolve(nodeId + ".json");
            if (Files.exists(nodeFile)) {
                JsonNode node = MAPPER.readTree(nodeFile.toFile());
                if (node.has("parentId") && !node.get("parentId").isNull()) {
                    String parentId = node.get("parentId").asText();
                    if (!parentId.isBlank() && !parentId.equals(nodeId)) {
                        return loadCr(worldId, parentId);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read parent for CR fallback {}/{}", worldId, nodeId, e);
        }

        return List.of();
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

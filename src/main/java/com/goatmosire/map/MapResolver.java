package com.goatmosire.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Resolves a hex map for any node by walking the parent chain.
 *
 * <p>Algorithm (mirrors GSim's WorldInfoBuilder pattern):
 * <ol>
 *   <li>Start at the requested node, walk up via parentId to root</li>
 *   <li>Reverse the chain so root is first</li>
 *   <li>Load root's full MapData (or empty default if absent)</li>
 *   <li>For each child node in the chain, apply its MapDiff (if present)</li>
 *   <li>Return the resolved MapData</li>
 * </ol>
 */
public final class MapResolver {

    private static final Logger log = LoggerFactory.getLogger(MapResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Maximum chain depth to prevent infinite loops. */
    private static final int MAX_CHAIN_DEPTH = 200;

    private MapResolver() {}

    // ── Public API ───────────────────────────────────────

    /**
     * Resolve the hex map for a given world + node by walking the parent chain.
     * Returns an empty default map if no map files exist at all.
     */
    public static MapData resolve(Path worldsDir, String worldId, String nodeId) {
        List<String> chain = walkParentChain(worldsDir, worldId, nodeId);
        if (chain.isEmpty()) return MapData.empty();

        // Root is first in chain (after reverse)
        MapData resolved = MapStore.loadFull(worldsDir, worldId, chain.get(0));
        if (resolved == null) {
            log.debug("No root map for {}/{}, using empty default", worldId, chain.get(0));
            resolved = MapData.empty();
        }
        List<MapData.CompressedRegion> baseCrs = resolved.compressedRegions();
        Map<String, MapDiff> chainDiffs = new LinkedHashMap<>();

        // Apply each child's diff
        for (int i = 1; i < chain.size(); i++) {
            MapDiff diff = MapStore.loadDiff(worldsDir, worldId, chain.get(i));
            if (diff != null && !diff.isEmpty()) {
                chainDiffs.put(chain.get(i), diff);
                resolved = applyDiff(resolved, diff, chain, baseCrs, chainDiffs);
            }
        }

        return resolved;
    }

    /**
     * Resolve the map for every node in the chain and return per-node history.
     */
    public static List<HistoryEntry> history(Path worldsDir, String worldId, String nodeId) {
        List<String> chain = walkParentChain(worldsDir, worldId, nodeId);
        List<HistoryEntry> entries = new ArrayList<>();

        MapData resolved = MapStore.loadFull(worldsDir, worldId, chain.get(0));
        if (resolved == null) resolved = MapData.empty();
        List<MapData.CompressedRegion> hBaseCrs = resolved.compressedRegions();
        Map<String, MapDiff> hChainDiffs = new LinkedHashMap<>();

        entries.add(new HistoryEntry(chain.get(0), resolved, null, MapStore.exists(worldsDir, worldId, chain.get(0))));

        for (int i = 1; i < chain.size(); i++) {
            String nid = chain.get(i);
            MapDiff diff = MapStore.loadDiff(worldsDir, worldId, nid);
            if (diff != null && !diff.isEmpty()) {
                hChainDiffs.put(nid, diff);
                resolved = applyDiff(resolved, diff, chain, hBaseCrs, hChainDiffs);
            }
            entries.add(new HistoryEntry(nid, resolved, diff, diff != null));
        }

        return entries;
    }

    public record HistoryEntry(String nodeId, MapData map, MapDiff diff, boolean hasOwnMap) {}

    // ── Parent chain walking ─────────────────────────────

    static List<String> walkParentChain(Path worldsDir, String worldId, String nodeId) {
        Path nodesDir = worldsDir.resolve(worldId).resolve("nodes");
        if (!Files.isDirectory(nodesDir)) return List.of();

        List<String> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String current = nodeId;

        while (current != null && !current.isBlank()) {
            if (!visited.add(current)) {
                log.warn("Cycle detected in parent chain at node: {}", current);
                break;
            }
            if (chain.size() >= MAX_CHAIN_DEPTH) {
                log.warn("Max chain depth ({}) exceeded", MAX_CHAIN_DEPTH);
                break;
            }
            chain.add(current);

            String parentId = readParentId(nodesDir, current);
            current = parentId;
        }

        Collections.reverse(chain);
        return chain;
    }

    private static String readParentId(Path nodesDir, String nodeId) {
        Path file = nodesDir.resolve(nodeId + ".json");
        if (!Files.exists(file)) return null;
        try {
            var node = MAPPER.readTree(file.toFile());
            if (node.has("parentId") && !node.get("parentId").isNull()) {
                String pid = node.get("parentId").asText();
                return pid.isBlank() ? null : pid;
            }
            return null;
        } catch (IOException e) {
            log.warn("Failed to read node file: {}", file, e);
            return null;
        }
    }

    // ── Diff application ─────────────────────────────────

    static MapData applyDiff(MapData base, MapDiff diff,
                              List<String> chain, List<MapData.CompressedRegion> baseCrs,
                              Map<String, MapDiff> chainDiffs) {
        // Hexes
        Map<String, MapData.HexCell> hexes = new LinkedHashMap<>(base.hexes());
        for (String key : diff.removed()) {
            hexes.remove(key);
        }
        for (var e : diff.changed().entrySet()) {
            hexes.put(e.getKey(), e.getValue());
        }

        // Provinces
        Map<String, MapData.Province> provinces = new LinkedHashMap<>(base.provinces());
        for (String p : diff.provincesRemoved()) {
            provinces.remove(p);
        }
        for (var e : diff.provincesChanged().entrySet()) {
            provinces.put(e.getKey(), e.getValue());
        }

        // Cities
        Map<String, MapData.City> cities = new LinkedHashMap<>(base.cities());
        for (String c : diff.citiesRemoved()) {
            cities.remove(c);
        }
        for (var e : diff.citiesAdded().entrySet()) {
            cities.put(e.getKey(), e.getValue());
        }

        // Rivers & Roads (full replacement from diff if non-empty)
        List<MapData.River> rivers = diff.riversAdded().isEmpty() ? base.rivers() : diff.riversAdded();
        List<MapData.Road> roads = diff.roadsAdded().isEmpty() ? base.roads() : diff.roadsAdded();

        // CR merge: child CRs (from its own compress) supersede parent CRs entirely.
        // Start from parent CRs, then let each child's CRs replace them if the child was compressed.
        List<MapData.CompressedRegion> crs = new ArrayList<>(baseCrs != null ? baseCrs : List.of());
        for (int i = 1; i < chain.size(); i++) {
            MapDiff d = chainDiffs.get(chain.get(i));
            if (d != null && !d.compressedRegions().isEmpty()) {
                crs = new ArrayList<>(d.compressedRegions());  // child CRs replace, not merge
            }
        }

        return new MapData(
            base.gridSize(), base.hexOrientation(),
            hexes, base.terrainBlocks(), provinces, cities, rivers, roads,
            base.terrainTypes(), crs, base.pathwayGroups()
        );
    }
}

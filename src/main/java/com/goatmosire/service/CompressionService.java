package com.goatmosire.service;

import com.gsim.map.MapData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Detects large contiguous same-terrain regions and compresses them into
 * polygon-bounded CompressedRegion objects, removing individual hexes from storage.
 *
 * <p>Also handles decompression: expanding a CompressedRegion back to individual hexes
 * when the user wants to edit that area.
 */
public class CompressionService {

    private static final Logger log = LoggerFactory.getLogger(CompressionService.class);
    private static final int[][] DIRS = {{1,0},{1,-1},{0,-1},{-1,0},{-1,1},{0,1}};

    /** Minimum region size for compression. Smaller regions are left as individual hexes. */
    public static final int DEFAULT_MIN_REGION_SIZE = 100;

    /**
     * Compress a MapData by finding large connected same-terrain regions,
     * removing them from hexes, and creating CompressedRegions instead.
     *
     * @return the list of newly created CompressedRegions
     */
    public static List<CompressedRegion> compress(MapData map, int minRegionSize) {
        if (map == null || map.hexes() == null || map.hexes().isEmpty())
            return List.of();

        Set<String> allKeys = new HashSet<>(map.hexes().keySet());
        List<CompressedRegion> regions = new ArrayList<>();
        int regionCounter = 0;

        while (!allKeys.isEmpty()) {
            String seed = allKeys.iterator().next();
            MapData.HexCell seedCell = map.hexes().get(seed);
            if (seedCell == null) { allKeys.remove(seed); continue; }
            String terrain = seedCell.terrain();

            // BFS to find connected component of same terrain
            Set<String> component = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(seed);
            component.add(seed);

            while (!queue.isEmpty()) {
                String key = queue.poll();
                int[] qr = MapData.parseHexKey(key);
                for (int[] d : DIRS) {
                    String nk = MapData.hexKey(qr[0] + d[0], qr[1] + d[1]);
                    if (allKeys.contains(nk) && !component.contains(nk)) {
                        MapData.HexCell nc = map.hexes().get(nk);
                        if (nc != null && terrain.equals(nc.terrain())) {
                            component.add(nk);
                            queue.add(nk);
                        }
                    }
                }
            }

            allKeys.removeAll(component);

            if (component.size() >= minRegionSize) {
                regionCounter++;
                String id = "cr" + regionCounter;
                String color = seedCell.color() != null ? seedCell.color() : terrainColor(terrain);
                boolean isWater = "water".equals(terrain);
                List<MapData.Pt> boundary = TerrainGeometry.hexSetToBoundary(component);

                CompressedRegion cr = new CompressedRegion(id, terrain, color, component, boundary, isWater);
                regions.add(cr);

                // Remove compressed hexes from the map
                for (String k : component) {
                    map.hexes().remove(k);
                }

                log.debug("Compressed: {} ({}×{})", id, terrain, component.size());
            }
        }

        log.info("Compression complete: {} regions created (≥{} hexes), {} hexes remain individual",
            regions.size(), minRegionSize, map.hexes().size());
        return regions;
    }

    /**
     * Decompress a single region back into individual hexes in the map.
     *
     * @return the number of hexes restored, or 0 if region not found
     */
    public static int decompress(MapData map, List<CompressedRegion> regions, String regionId) {
        CompressedRegion target = null;
        for (CompressedRegion cr : regions) {
            if (cr.id.equals(regionId)) { target = cr; break; }
        }
        if (target == null) return 0;

        return decompressRegion(map, regions, target);
    }

    /**
     * Decompress the region at a specific hex coordinate.
     *
     * @return the number of hexes restored, or 0 if no region covers this hex
     */
    public static int decompressAt(MapData map, List<CompressedRegion> regions, int q, int r) {
        String key = MapData.hexKey(q, r);

        // Check if already an individual hex
        if (map.hexes().containsKey(key)) return 0;

        // Find the first region whose hexKeys contain this key
        for (CompressedRegion cr : regions) {
            if (cr.hexKeys != null && cr.hexKeys.contains(key)) {
                return decompressRegion(map, regions, cr);
            }
            // Also check boundary if hexKeys not available
            if (cr.boundary != null && !cr.boundary.isEmpty()) {
                double[] px = TerrainGeometry.hexToPixel(q, r);
                if (TerrainGeometry.pointInPolygon(px[0], px[1], cr.boundary)) {
                    if (cr.hexKeys == null) cr.hexKeys = new HashSet<>();
                    cr.hexKeys.add(key);
                    return decompressRegion(map, regions, cr);
                }
            }
        }
        return 0;
    }

    private static int decompressRegion(MapData map, List<CompressedRegion> regions, CompressedRegion cr) {
        if (cr.hexKeys == null || cr.hexKeys.isEmpty()) return 0;

        int riverMask = 0;
        for (String key : cr.hexKeys) {
            map.hexes().put(key, new MapData.HexCell(cr.color, cr.terrain, null, null, "", riverMask));
        }
        int count = cr.hexKeys.size();
        regions.remove(cr);
        log.info("Decompressed: {} ({}×{})", cr.id, cr.terrain, count);
        return count;
    }

    private static String terrainColor(String t) {
        return switch (t) {
            case "mountain" -> "#6B6B6B";
            case "hills"    -> "#A0522D";
            case "lowland"  -> "#5B8C3E";
            case "plains"   -> "#B8A88A";
            case "swamp"    -> "#556B2F";
            case "water"    -> "#3295D2";
            default         -> "#5B8C3E";
        };
    }
}

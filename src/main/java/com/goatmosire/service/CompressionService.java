package com.goatmosire.service;

import com.gsim.map.MapData;
import com.gsim.map.MapData.CompressedRegion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Detects large contiguous same-terrain regions in {@link MapData#hexes()} and creates
 * {@link CompressedRegion} entries for efficient rendering.
 *
 * <p>IMPORTANT: This service does NOT remove hexes from hexes(). hexes() is always the
 * authoritative data source. compressedRegions is a pure rendering optimization — it can
 * be regenerated at any time by re-running {@link #compress}.
 */
public class CompressionService {

    private static final Logger log = LoggerFactory.getLogger(CompressionService.class);
    private static final int[][] DIRS = {{1,0},{1,-1},{0,-1},{-1,0},{-1,1},{0,1}};

    /** Minimum region size for compression. Smaller regions are left as individual hexes. */
    public static final int DEFAULT_MIN_REGION_SIZE = 100;

    /**
     * Scan hexes for large contiguous same-terrain regions and create CompressedRegions.
     * Does NOT remove hexes from map.hexes() — hexes remain the authoritative data source.
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

                CompressedRegion cr = new CompressedRegion(
                    id, terrain, color, boundary, isWater, component);
                regions.add(cr);

                log.debug("Compressed: {} ({}×{})", id, terrain, component.size());
            }
        }

        log.info("Compression complete: {} regions created (≥{} hexes), {} hexes total",
            regions.size(), minRegionSize, map.hexes().size());
        // Sort ascending by size: small regions render on top, preventing large-region overlap
        regions.sort(Comparator.comparingInt(CompressedRegion::size));
        return regions;
    }

    // ── Decompression (just remove CR entries, hexes are already in hexes()) ──

    /**
     * Decompress a single region. Since hexes remain in map.hexes() at all times,
     * this simply removes the region from the list.
     *
     * @return the number of hexes that were in the region, or 0 if not found
     */
    public static int decompress(List<CompressedRegion> regions, String regionId) {
        CompressedRegion target = null;
        for (CompressedRegion cr : regions) {
            if (cr.id().equals(regionId)) { target = cr; break; }
        }
        if (target == null) return 0;
        int count = target.size();
        regions.remove(target);
        log.info("Decompressed: {} ({}×{})", target.id(), target.terrain(), count);
        return count;
    }

    /**
     * Decompress the region covering hex (q, r).
     *
     * @return the number of hexes restored, or 0 if no region covers this hex
     */
    public static int decompressAt(List<CompressedRegion> regions, int q, int r) {
        String key = MapData.hexKey(q, r);

        for (CompressedRegion cr : regions) {
            if (cr.hexKeys() != null && cr.hexKeys().contains(key)) {
                int count = cr.size();
                regions.remove(cr);
                log.info("Decompressed: {} ({}×{})", cr.id(), cr.terrain(), count);
                return count;
            }
            // Also check boundary if hexKeys available
            if (cr.boundary() != null && !cr.boundary().isEmpty()) {
                double[] px = TerrainGeometry.hexToPixel(q, r);
                if (TerrainGeometry.pointInPolygon(px[0], px[1], cr.boundary())) {
                    int count = cr.size();
                    regions.remove(cr);
                    log.info("Decompressed: {} ({}×{})", cr.id(), cr.terrain(), count);
                    return count;
                }
            }
        }
        return 0;
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

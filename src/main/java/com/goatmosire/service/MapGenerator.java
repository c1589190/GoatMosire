package com.goatmosire.service;

import com.gsim.map.MapData;
import java.util.*;

/**
 * Voronoi + fractal noise continent generator for hex maps.
 *
 * Algorithm:
 *   1. Place random continent seed points (1 large + N small islands)
 *   2. For each hex, compute weighted distance to nearest seed
 *   3. Apply fractal noise overlay (multi-octave hash noise)
 *   4. Add coastal erosion via sigmoid threshold
 *   5. Classify elevation bands into terrain types
 */
public class MapGenerator {

    private final Random rng;
    private final int radius;
    private double[] seedX;
    private double[] seedY;
    private double[] seedWeight;

    // Noise permutation table (classic Perlin-style)
    private static final int[] PERM = new int[512];
    static {
        int[] p = {151,160,137,91,90,15,131,13,201,95,96,53,194,233,7,225,140,36,103,30,69,142,8,99,37,240,21,10,23,
            190,6,148,247,120,234,75,0,26,197,62,94,252,219,203,117,35,11,32,57,177,33,88,237,149,56,87,174,20,125,136,171,
            168,68,175,74,165,71,134,139,48,27,166,77,146,158,231,83,111,229,122,60,211,133,230,220,105,92,41,55,46,245,40,244,
            102,143,54,65,25,63,161,1,216,80,73,209,76,132,187,208,89,18,169,200,196,135,130,116,188,159,86,164,100,109,198,173,
            186,3,64,52,217,226,250,124,123,5,202,38,147,118,126,255,82,85,212,207,206,59,227,47,16,58,17,182,189,28,42,223,183,
            170,213,119,248,152,2,44,154,163,70,221,153,101,155,167,43,172,9,129,22,39,253,19,98,108,110,79,113,224,232,178,185,
            112,104,218,246,97,228,251,34,242,193,238,210,144,12,191,179,162,241,81,51,145,235,249,14,239,107,49,192,214,31,181,
            199,106,157,184,84,204,176,115,121,50,45,127,4,150,254,138,236,205,93,222,114,67,29,24,72,243,141,128,195,78,66,215,61,156,180};
        for (int i = 0; i < 512; i++) PERM[i] = p[i & 255];
    }

    public MapGenerator(long seed, int mapRadius) {
        this.rng = new Random(seed);
        this.radius = mapRadius;
    }

    /**
     * Set continent center points. Each pair (x,y) is a seed location, weight controls mass.
     */
    public void placeSeeds(int mainCount, int islandCount) {
        int total = mainCount + islandCount;
        seedX = new double[total];
        seedY = new double[total];
        seedWeight = new double[total];

        // Main continents: spread around center with some randomness
        for (int i = 0; i < mainCount; i++) {
            double angle = (2 * Math.PI * i / mainCount) + rng.nextGaussian() * 0.3;
            double dist = radius * (0.1 + rng.nextDouble() * 0.3);
            seedX[i] = Math.cos(angle) * dist;
            seedY[i] = Math.sin(angle) * dist;
            seedWeight[i] = 1.0 + rng.nextDouble() * 0.5;
        }

        // Islands: random scatter
        for (int i = mainCount; i < total; i++) {
            double angle = rng.nextDouble() * 2 * Math.PI;
            double dist = radius * (0.2 + rng.nextDouble() * 0.7);
            seedX[i] = Math.cos(angle) * dist;
            seedY[i] = Math.sin(angle) * dist;
            seedWeight[i] = 0.15 + rng.nextDouble() * 0.35;
        }
    }

    /**
     * Generate a full MapData from the placed seeds.
     */
    public MapData generate(double landRatio, double mountainRatio) {
        // Hex grid: flat-top, radius determines hex count
        int maxQ = radius, minQ = -radius;
        int maxR = radius, minR = -radius;

        var hexes = new LinkedHashMap<String, MapData.HexCell>();

        for (int q = minQ; q <= maxQ; q++) {
            for (int r = minR; r <= maxR; r++) {
                int s = -(q + r);
                if (Math.abs(q) + Math.abs(r) + Math.abs(s) > 2 * radius) continue;

                // Convert axial to pixel for noise sampling
                double px = q * 1.0 + r * 0.5;
                double py = r * 0.8660254;

                // Compute elevation from Voronoi distance + noise
                double elev = computeElevation(px, py);

                if (elev < 0.5) {
                    hexes.put(MapData.hexKey(q, r),
                        new MapData.HexCell("#3295D2", "water", null, null, "", 0));
                    continue;
                }

                // Terrain classification by distance to nearest seed
                // Find nearest seed and compute distance
                double minWdist = Double.MAX_VALUE;
                int nearestSeed = 0;
                for (int i = 0; i < seedX.length; i++) {
                    double dx = px - seedX[i];
                    double dy = py - seedY[i];
                    double wdist = Math.sqrt(dx*dx+dy*dy) / seedWeight[i];
                    if (wdist < minWdist) { minWdist = wdist; nearestSeed = i; }
                }
                double coreRadius = seedWeight[nearestSeed] * radius * 0.30;
                double landT = minWdist / coreRadius;  // 0=center, 1=coast

                String terrain;
                String color;
                if (landT < 0.2) {
                    terrain = "mountain"; color = "#808080";
                } else if (landT < 0.4) {
                    terrain = "hills"; color = "#BDB76B";
                } else if (landT < 0.7) {
                    terrain = rng.nextDouble() < 0.5 ? "forest" : "plains";
                    color = terrain.equals("forest") ? "#228B22" : "#6CC261";
                } else {
                    terrain = rng.nextDouble() < 0.3 ? "swamp" : "plains";
                    color = terrain.equals("swamp") ? "#556B2F" : "#6CC261";
                }

                hexes.put(MapData.hexKey(q, r),
                    new MapData.HexCell(color, terrain, null, null, "", 0));
            }
        }

        return new MapData(30, false, hexes,
            new LinkedHashMap<>(), new LinkedHashMap<>(), List.of(), List.of(),
            defaultTerrainTypes());
    }

    private double computeElevation(double px, double py) {
        // Find nearest continent seed (weighted distance)
        double minWdist = Double.MAX_VALUE;
        int nearestSeed = 0;
        for (int i = 0; i < seedX.length; i++) {
            double dx = px - seedX[i];
            double dy = py - seedY[i];
            double dist = Math.sqrt(dx * dx + dy * dy);
            double wdist = dist / seedWeight[i];
            if (wdist < minWdist) { minWdist = wdist; nearestSeed = i; }
        }

        // Core continent radius: within seedWeight * radius * 0.30 → solid land
        // Beyond that, use hash-based jitter for rough edges
        double coreRadius = seedWeight[nearestSeed] * radius * 0.30;
        if (minWdist < coreRadius) return 1.0;  // deep interior

        // Edge zone: coreRadius to coreRadius * 1.5
        // Use hash noise to create irregular coastline
        double edgeWidth = coreRadius * 0.5;
        double edgeT = (minWdist - coreRadius) / edgeWidth;
        if (edgeT >= 1.0) return 0.0;  // deep ocean

        // Hash-based jitter: randomize the coastline
        int hx = (int) Math.floor(px * 3.7);
        int hy = (int) Math.floor(py * 3.7);
        double hash = hash2d(hx, hy);
        double threshold = 0.5 + (hash - 0.5) * 0.8;  // jitter around 0.5

        return edgeT < threshold ? 1.0 : 0.0;
    }

    /** Simple 2D hash returning 0..1 */
    private static double hash2d(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        return (h & 0x7fffffff) / (double) 0x7fffffff;
    }

    // ── Removed Perlin noise (fbm, noise, fade, lerp, grad, sigmoid) ──

    // ── Helpers ───────────────────────────────────────────

    private static LinkedHashMap<String, MapData.TerrainType> defaultTerrainTypes() {
        var tt = new LinkedHashMap<String, MapData.TerrainType>();
        tt.put("water",    new MapData.TerrainType("水",   "#3295D2", 1, 0, 0, 99, "水域"));
        tt.put("plains",   new MapData.TerrainType("平原", "#6CC261", 3, 1, 1, 1,  "平原"));
        tt.put("forest",   new MapData.TerrainType("森林", "#228B22", 2, 1, 2, 2,  "森林"));
        tt.put("mountain", new MapData.TerrainType("山地", "#808080", 0, 2, 5, 3,  "山地"));
        tt.put("desert",   new MapData.TerrainType("沙漠", "#DDC88D", 1, 2, 1, 2,  "沙漠"));
        tt.put("swamp",    new MapData.TerrainType("沼泽", "#556B2F", 2, 0, 1, 2,  "沼泽"));
        tt.put("tundra",   new MapData.TerrainType("冻土", "#A8C4D8", 1, 1, 1, 2,  "冻土"));
        tt.put("hills",    new MapData.TerrainType("丘陵", "#BDB76B", 2, 1, 3, 2,  "丘陵"));
        return tt;
    }

    public static MapData generate(String worldId, long seed, int mapRadius,
                                   int mainCount, int islandCount,
                                   double landRatio, double mountainRatio) {
        var gen = new MapGenerator(seed, mapRadius);
        gen.placeSeeds(mainCount, islandCount);
        return gen.generate(landRatio, mountainRatio);
    }
}

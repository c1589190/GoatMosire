package com.goatmosire.service;

import com.gsim.map.MapData;
import java.util.*;

/**
 * Voronoi + ridge-line continent generator for hex maps.
 *
 * Algorithm:
 *   1. Place continent seeds (1 large + N small islands)
 *   2. For each seed, generate 2-4 mountain ridge lines radiating outward
 *   3. Voronoi distance field determines land/ocean boundary
 *   4. Hash jitter on coastline for irregular edges (with smoothness control)
 *   5. Terrain: mountain (near ridge) → hills → forest/plains → swamp/coast
 */
public class MapGenerator {

    private final Random rng;
    private final int radius;
    private double[] seedX, seedY, seedWeight;
    // Ridge lines: for each seed, list of {angle, length} pairs
    private List<double[]>[] seedRidges;

    public MapGenerator(long seed, int mapRadius) {
        this.rng = new Random(seed);
        this.radius = mapRadius;
    }

    /**
     * Place continent seeds with random positions and weights.
     * Also generate mountain ridge lines for each seed.
     */
    @SuppressWarnings("unchecked")
    public void placeSeeds(int mainCount, int islandCount) {
        int total = mainCount + islandCount;
        seedX = new double[total];
        seedY = new double[total];
        seedWeight = new double[total];
        seedRidges = new List[total];

        // Main continents
        for (int i = 0; i < mainCount; i++) {
            double angle = (2 * Math.PI * i / mainCount) + rng.nextGaussian() * 0.3;
            double dist = radius * (0.1 + rng.nextDouble() * 0.3);
            seedX[i] = Math.cos(angle) * dist;
            seedY[i] = Math.sin(angle) * dist;
            seedWeight[i] = 1.0 + rng.nextDouble() * 0.5;
            seedRidges[i] = generateRidges(seedX[i], seedY[i], seedWeight[i], 3 + rng.nextInt(3));
        }

        // Islands
        for (int i = mainCount; i < total; i++) {
            double angle = rng.nextDouble() * 2 * Math.PI;
            double dist = radius * (0.2 + rng.nextDouble() * 0.7);
            seedX[i] = Math.cos(angle) * dist;
            seedY[i] = Math.sin(angle) * dist;
            seedWeight[i] = 0.15 + rng.nextDouble() * 0.35;
            seedRidges[i] = generateRidges(seedX[i], seedY[i], seedWeight[i], 1 + rng.nextInt(2));
        }
    }

    /** Generate ridge lines radiating from a seed center */
    private List<double[]> generateRidges(double cx, double cy, double weight, int count) {
        List<double[]> ridges = new ArrayList<>();
        double baseAngle = rng.nextDouble() * 2 * Math.PI;
        double maxLen = weight * radius * 0.30;  // same as coreRadius
        for (int i = 0; i < count; i++) {
            double angle = baseAngle + (2 * Math.PI * i / count) + rng.nextGaussian() * 0.3;
            double len = maxLen * (0.5 + rng.nextDouble() * 0.5);
            ridges.add(new double[]{angle, len});
        }
        return ridges;
    }

    /**
     * Generate MapData from placed seeds.
     * @param landRatio  0-1, target land fraction (approximate)
     * @param coastRoughness 0-1, 0=smooth circles, 1=max jitter
     */
    public MapData generate(double landRatio, double coastRoughness) {
        var hexes = new LinkedHashMap<String, MapData.HexCell>();
        int maxQ = radius, minQ = -radius;
        int maxR = radius, minR = -radius;

        for (int q = minQ; q <= maxQ; q++) {
            for (int r = minR; r <= maxR; r++) {
                int s = -(q + r);
                if (Math.abs(q) + Math.abs(r) + Math.abs(s) > 2 * radius) continue;

                double px = q + r * 0.5;
                double py = r * 0.8660254;

                // Find nearest seed
                double minWdist = Double.MAX_VALUE;
                int nearestIdx = 0;
                for (int i = 0; i < seedX.length; i++) {
                    double dx = px - seedX[i], dy = py - seedY[i];
                    double wdist = Math.sqrt(dx*dx + dy*dy) / seedWeight[i];
                    if (wdist < minWdist) { minWdist = wdist; nearestIdx = i; }
                }

                double coreRadius = seedWeight[nearestIdx] * radius * 0.30;
                double jitterRadius = coreRadius * (0.5 + coastRoughness * 0.5);

                // Deep interior: solid land
                boolean isLand;
                if (minWdist < coreRadius * 0.7) {
                    isLand = true;
                } else if (minWdist > coreRadius * 1.4 + jitterRadius) {
                    isLand = false;
                } else {
                    // Edge zone: hash jitter for coastline
                    double edgeT = (minWdist - coreRadius * 0.7) / (coreRadius * 0.7 + jitterRadius);
                    double threshold = hash2d((int)(px * 5), (int)(py * 5));
                    // Blend: smooth threshold near 0.5, scattered near edges
                    double jitter = (threshold - 0.5) * coastRoughness;
                    isLand = edgeT < (0.45 + jitter);
                }

                if (!isLand) {
                    hexes.put(MapData.hexKey(q, r),
                        new MapData.HexCell("#3295D2", "water", null, null, "", 0));
                    continue;
                }

                // Terrain: mountain near ridge lines, then distance-based
                String terrain = classifyTerrain(px, py, nearestIdx, minWdist, coreRadius);
                String color = terrainColor(terrain);

                hexes.put(MapData.hexKey(q, r),
                    new MapData.HexCell(color, terrain, null, null, "", 0));
            }
        }

        return new MapData(30, false, hexes,
            new LinkedHashMap<>(), new LinkedHashMap<>(), List.of(), List.of(),
            defaultTerrainTypes());
    }

    /** Classify terrain based on distance to nearest ridge line + distance from center */
    private String classifyTerrain(double px, double py, int seedIdx, double minWdist, double coreRadius) {
        double landT = minWdist / coreRadius;

        // Check distance to nearest ridge line
        double minRidgeDist = Double.MAX_VALUE;
        for (double[] ridge : seedRidges[seedIdx]) {
            double angle = ridge[0], len = ridge[1];
            // Point on ridge at distance d from center
            double rx = seedX[seedIdx] + Math.cos(angle) * len * 0.5;
            double ry = seedY[seedIdx] + Math.sin(angle) * len * 0.5;
            double rdx = Math.cos(angle), rdy = Math.sin(angle);

            // Distance from point to ridge line
            double dx = px - rx, dy = py - ry;
            double proj = dx * rdx + dy * rdy;
            double perpDist;
            if (proj < -len * 0.3 || proj > len * 0.3) {
                // Beyond ridge endpoints: distance to endpoint
                double ex = rx + Math.signum(proj) * rdx * len * 0.3;
                double ey = ry + Math.signum(proj) * rdy * len * 0.3;
                perpDist = Math.sqrt((px-ex)*(px-ex) + (py-ey)*(py-ey));
            } else {
                perpDist = Math.abs(-rdy * dx + rdx * dy);
            }
            if (perpDist < minRidgeDist) minRidgeDist = perpDist;
        }
        double ridgeNorm = minRidgeDist / (coreRadius * 0.15);

        // Mountain: on or very near ridge, or deep center
        if (ridgeNorm < 0.5 || landT < 0.10) return "mountain";
        // Hills: near ridge or high elevation
        if (ridgeNorm < 1.0 || landT < 0.25) return "hills";
        // Mid-elevation: forest or plains
        if (landT < 0.5) return rng.nextDouble() < 0.5 ? "forest" : "plains";
        // Low/coastal: plains or swamp
        if (landT < 0.8) return "plains";
        return rng.nextDouble() < 0.3 ? "swamp" : "plains";
    }

    private String terrainColor(String terrain) {
        return switch (terrain) {
            case "mountain" -> "#808080";
            case "hills" -> "#BDB76B";
            case "forest" -> "#228B22";
            case "plains" -> "#6CC261";
            case "swamp" -> "#556B2F";
            default -> "#6CC261";
        };
    }

    /** Simple 2D hash returning 0..1 */
    private static double hash2d(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        return (h & 0x7fffffff) / (double) 0x7fffffff;
    }

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
                                   double landRatio, double coastRoughness) {
        var gen = new MapGenerator(seed, mapRadius);
        gen.placeSeeds(mainCount, islandCount);
        return gen.generate(landRatio, coastRoughness);
    }
}

package com.goatmosire.service;

import com.gsim.map.MapData;
import java.util.*;

/**
 * Ridge-Diffusion continent generator.
 *
 * Algorithm:
 *   1. Generate mountain ridge lines (Bezier curves) → continental skeleton
 *   2. For each hex, distance to nearest ridge → land if within diffusion radius
 *   3. Perlin/Simplex noise modulates coastline + terrain variation
 *   4. Terrain bands parallel to ridges: mountain → hills → forest → plains → swamp
 *   5. Islands = short isolated ridge fragments + noise-elevated sea floor
 */
public class MapGenerator {

    private final Random rng;
    private final int radius;
    // Ridge lines: each is a list of {qx, qy} control points in axial space
    private List<Ridge> ridges;
    // OpenSimplex2S evaluator
    private final SimplexNoise noise;

    // ── Ridge ─────────────────────────────────────────────
    record Ridge(List<Pt> points, double weight) {}
    record Pt(double x, double y) {}

    public MapGenerator(long seed, int mapRadius) {
        this.rng = new Random(seed);
        this.radius = mapRadius;
        this.noise = new SimplexNoise(seed);
    }

    /** Generate ridge lines radiating from center */
    public void placeRidges(int mainCount, int fragmentCount) {
        ridges = new ArrayList<>();
        double baseAngle = rng.nextDouble() * 2 * Math.PI;

        // Main ridges: long sweeping curves across the map
        for (int i = 0; i < mainCount; i++) {
            double angle = baseAngle + (2 * Math.PI * i / mainCount) + rng.nextGaussian() * 0.4;
            double len = radius * (0.9 + rng.nextDouble() * 0.1);
            List<Pt> pts = new ArrayList<>();
            // Start near center
            pts.add(new Pt(rng.nextGaussian() * radius * 0.05, rng.nextGaussian() * radius * 0.05));
            // Mid control point with offset
            double mx = Math.cos(angle) * len * 0.4 + rng.nextGaussian() * radius * 0.1;
            double my = Math.sin(angle) * len * 0.4 + rng.nextGaussian() * radius * 0.1;
            pts.add(new Pt(mx, my));
            // End at edge
            double ex = Math.cos(angle) * len + rng.nextGaussian() * radius * 0.08;
            double ey = Math.sin(angle) * len + rng.nextGaussian() * radius * 0.08;
            pts.add(new Pt(ex, ey));
            ridges.add(new Ridge(pts, 0.8 + rng.nextDouble() * 0.4));
        }

        // Fragment ridges (islands): short segments away from center
        for (int i = 0; i < fragmentCount; i++) {
            double angle = rng.nextDouble() * 2 * Math.PI;
            double dist = radius * (0.35 + rng.nextDouble() * 0.55);
            double cx = Math.cos(angle) * dist;
            double cy = Math.sin(angle) * dist;
            double flen = radius * (0.06 + rng.nextDouble() * 0.15);
            double fangle = angle + rng.nextGaussian() * 0.6;
            List<Pt> pts = new ArrayList<>();
            pts.add(new Pt(cx - Math.cos(fangle)*flen*0.5, cy - Math.sin(fangle)*flen*0.5));
            pts.add(new Pt(cx + Math.cos(fangle)*flen*0.5, cy + Math.sin(fangle)*flen*0.5));
            ridges.add(new Ridge(pts, 0.2 + rng.nextDouble() * 0.3));
        }
    }

    public MapData generate(double landRatio) {
        var hexes = new LinkedHashMap<String, MapData.HexCell>();
        int maxQ = radius, minQ = -radius;
        double maxLandDist = radius * 0.18;  // max diffusion distance from ridge

        for (int q = minQ; q <= maxQ; q++) {
            for (int r = -radius; r <= radius; r++) {
                int s = -(q + r);
                if (Math.abs(q) + Math.abs(r) + Math.abs(s) > 2 * radius) continue;

                double px = q + r * 0.5;
                double py = r * 0.8660254;

                // Find nearest ridge and distance
                RidgeInfo info = nearestRidge(px, py);
                if (info == null) {
                    hexes.put(MapData.hexKey(q, r), waterCell());
                    continue;
                }

                double ridgeDist = info.distance;
                double ridgeWeight = info.weight;

                // Diffusion radius: ridge weight controls how far land extends
                double diffusionRadius = ridgeWeight * maxLandDist;

                // Noise modulation on the coastline
                double nx = noise.noise2(px * 0.008, py * 0.008);
                double ny = noise.noise2(px * 0.012 + 100, py * 0.012 + 100);
                double coastalNoise = (nx * 0.6 + ny * 0.4);

                // Land if within diffusion ± noise
                double effectiveDist = ridgeDist - coastalNoise * diffusionRadius * 0.25;
                if (effectiveDist > diffusionRadius) {
                    hexes.put(MapData.hexKey(q, r), waterCell());
                    continue;
                }

                // Normalize position 0=ridge, 1=coast
                double landT = Math.max(0, Math.min(1, effectiveDist / diffusionRadius));

                // Terrain bands
                String terrain = classifyTerrain(landT, px, py, ridgeDist, diffusionRadius);
                hexes.put(MapData.hexKey(q, r),
                    new MapData.HexCell(terrainColor(terrain), terrain, null, null, "", 0));
            }
        }

        return new MapData(30, false, hexes,
            new LinkedHashMap<>(), new LinkedHashMap<>(), List.of(), List.of(),
            defaultTerrainTypes());
    }

    record RidgeInfo(double distance, double weight) {}

    private RidgeInfo nearestRidge(double px, double py) {
        double bestDist = Double.MAX_VALUE;
        double bestWeight = 0;
        for (Ridge r : ridges) {
            double d = distToRidge(px, py, r.points);
            if (d < bestDist) { bestDist = d; bestWeight = r.weight; }
        }
        return bestDist < Double.MAX_VALUE ? new RidgeInfo(bestDist, bestWeight) : null;
    }

    /** Distance from point to a polyline (ridge) */
    private double distToRidge(double px, double py, List<Pt> pts) {
        double minD = Double.MAX_VALUE;
        for (int i = 0; i < pts.size() - 1; i++) {
            Pt a = pts.get(i), b = pts.get(i+1);
            double dx = b.x - a.x, dy = b.y - a.y;
            double len2 = dx*dx + dy*dy;
            if (len2 < 0.001) {
                minD = Math.min(minD, Math.sqrt((px-a.x)*(px-a.x) + (py-a.y)*(py-a.y)));
            } else {
                double t = Math.max(0, Math.min(1, ((px-a.x)*dx + (py-a.y)*dy) / len2));
                double cx = a.x + t * dx, cy = a.y + t * dy;
                minD = Math.min(minD, Math.sqrt((px-cx)*(px-cx) + (py-cy)*(py-cy)));
            }
        }
        return minD;
    }

    /** Classify terrain based on distance from ridge */
    private String classifyTerrain(double landT, double px, double py, double ridgeDist, double diffusion) {
        // Add elevation variation with noise
        double eNx = noise.noise2(px * 0.025, py * 0.025);
        double elevNoise = eNx * 0.15;

        // Combine structural position + noise
        double elev = landT + elevNoise;

        // Ridge line: mountain spine
        if (landT < 0.08) return "mountain";
        // High elevation near ridge
        if (elev < 0.20) return "mountain";
        if (elev < 0.35) return "hills";
        // Mid elevation
        if (elev < 0.55) return rng.nextDouble() < 0.4 ? "forest" : "plains";
        // Low elevation
        if (elev < 0.80) return "plains";
        // Coastal
        return rng.nextDouble() < 0.35 ? "swamp" : "plains";
    }

    private MapData.HexCell waterCell() {
        return new MapData.HexCell("#3295D2", "water", null, null, "", 0);
    }

    private static String terrainColor(String t) {
        return switch (t) {
            case "mountain" -> "#808080";
            case "hills" -> "#BDB76B";
            case "forest" -> "#228B22";
            case "plains" -> "#6CC261";
            case "swamp" -> "#556B2F";
            default -> "#6CC261";
        };
    }

    // ── OpenSimplex2S (compact, embedded) ──────────────────
    static class SimplexNoise {
        private final long seed;

        SimplexNoise(long seed) { this.seed = seed; }

        double noise2(double x, double y) {
            // Simple gradient noise using 2D hash
            int xi = (int)Math.floor(x), yi = (int)Math.floor(y);
            double xf = x - xi, yf = y - yi;

            double n00 = dotGrid(xi, yi, xf, yf);
            double n10 = dotGrid(xi+1, yi, xf-1, yf);
            double n01 = dotGrid(xi, yi+1, xf, yf-1);
            double n11 = dotGrid(xi+1, yi+1, xf-1, yf-1);

            double u = smooth(xf), v = smooth(yf);
            double nx0 = lerp(n00, n10, u);
            double nx1 = lerp(n01, n11, u);
            return lerp(nx0, nx1, v);
        }

        private double dotGrid(int ix, int iy, double dx, double dy) {
            long h = hash(ix, iy);
            double angle = (h & 0xFFFF) * (2.0 * Math.PI / 65536.0);
            double gx = Math.cos(angle), gy = Math.sin(angle);
            return gx * dx + gy * dy;
        }

        private long hash(int x, int y) {
            long h = seed;
            h = h * 6364136223846793005L + x;
            h = h * 6364136223846793005L + y;
            h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
            h = (h ^ (h >>> 33)) * 0xC4CEB9FE1A85EC53L;
            return h ^ (h >>> 33);
        }

        private static double smooth(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
        private static double lerp(double a, double b, double t) { return a + t * (b - a); }
    }

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

    // ── Public factory ────────────────────────────────────

    public static MapData generate(String worldId, long seed, int mapRadius,
                                   int mainRidges, int fragments,
                                   double landRatio, double coastRoughness) {
        var gen = new MapGenerator(seed, mapRadius);
        gen.placeRidges(mainRidges, fragments);
        return gen.generate(landRatio);
    }
}

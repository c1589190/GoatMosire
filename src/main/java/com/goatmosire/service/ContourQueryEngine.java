package com.goatmosire.service;

import java.util.*;

/**
 * Real-time terrain query engine.
 * Given a ContinentContour, computes height/terrain for any hex coordinate lazily.
 *
 * Uses an LRU cache for recently queried coordinates (reduces re-computation
 * when the same hex is queried multiple times — common in rendering loops).
 */
public class ContourQueryEngine {

    private final ContinentContour contour;
    private final SimplexNoise noise;

    // LRU cache: coordinate key → TerrainSample
    private final Map<String, TerrainSample> cache;
    private static final int MAX_CACHE = 5000;

    public ContourQueryEngine(ContinentContour contour) {
        this.contour = contour;
        this.noise = new SimplexNoise(contour.seed);
        this.cache = new LinkedHashMap<>(MAX_CACHE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, TerrainSample> e) {
                return size() > MAX_CACHE;
            }
        };
    }

    // ── Public API ────────────────────────────────────────

    public record TerrainSample(double height, String terrain, String color) {
        public boolean isWater() { return "water".equals(terrain); }
    }

    /** Query terrain for a hex at axial coordinates (q, r) */
    public TerrainSample query(int q, int r) {
        String key = q + "_" + r;
        TerrainSample cached = cache.get(key);
        if (cached != null) return cached;

        TerrainSample sample = compute(q, r);
        cache.put(key, sample);
        return sample;
    }

    /** Bulk-query a region and return as MapData (for legacy full-map consumers) */
    public com.gsim.map.MapData materialize(int minQ, int maxQ, int minR, int maxR) {
        var hexes = new LinkedHashMap<String, com.gsim.map.MapData.HexCell>();
        for (int q = minQ; q <= maxQ; q++) {
            for (int r = minR; r <= maxR; r++) {
                int s = -(q + r);
                if (Math.abs(q) + Math.abs(r) + Math.abs(s) > 2 * contour.radius) continue;
                TerrainSample ts = query(q, r);
                hexes.put(q + "_" + r,
                    new com.gsim.map.MapData.HexCell(ts.color, ts.terrain, null, null, "", 0));
            }
        }
        return new com.gsim.map.MapData(30, false, hexes,
            new LinkedHashMap<>(), new LinkedHashMap<>(), List.of(), List.of(),
            MapGenerator.defaultTerrainTypes());
    }

    // ═══════════════════════════════════════════════════════
    //  Query computation (mirrors MapGenerator.generate)
    // ═══════════════════════════════════════════════════════

    private TerrainSample compute(int q, int r) {
        double px = q + r * 0.5;
        double py = r * 0.8660254;

        // Domain warp
        double wpx = px + noise.noise2(px * 0.018, py * 0.018) * 10;
        double wpy = py + noise.noise2(px * 0.018 + 70, py * 0.018 + 70) * 10;

        // Ridge height
        double ridgeH = computeRidgeHeight(wpx, wpy);

        // Shelf noise
        double shelf = noise.noise2(wpx * contour.shelfFreq, wpy * contour.shelfFreq);
        shelf = Math.max(0, shelf * 0.35 + 0.15);

        // Multi-band noise
        double n1 = noise.noise2(wpx * contour.lowFreq  + 100, wpy * contour.lowFreq  + 100);
        double n2 = noise.noise2(wpx * contour.midFreq  + 300, wpy * contour.midFreq  + 300);
        double n3 = noise.noise2(wpx * contour.highFreq + 500, wpy * contour.highFreq + 500);
        double multi = n1 * 0.40 + n2 * 0.25 + n3 * 0.12;

        // Valley
        double valley = computeValleyPenalty(wpx, wpy);

        // Height assembly
        double height = ridgeH * 0.55 + shelf * 0.40 + multi * 0.50 - valley;
        height = Math.max(0, height);
        height = Math.pow(height, 0.85);

        // Coast noise → sea level
        double coastNoise = noise.noise2(wpx * contour.coastFreq + 77, wpy * contour.coastFreq + 77);
        double seaLevel = contour.baseSeaLevel + coastNoise * 0.35;

        if (height < seaLevel) {
            return new TerrainSample(height, "water", "#3295D2");
        }

        // Classification
        String terrain = classify(height, px, py);
        String color = terrainColor(terrain);
        return new TerrainSample(height, terrain, color);
    }

    // ═══════════════════════════════════════════════════════
    //  Mirror of MapGenerator component functions
    // ═══════════════════════════════════════════════════════

    private double computeRidgeHeight(double px, double py) {
        double best = 0;
        for (ContinentContour.Ridge r : contour.ridges) {
            double d = distToRidge(px, py, r.points);
            double k = 7.0 + r.weight * 2.0;
            double h = Math.exp(-d * k / contour.radius);
            if (h > best) best = h;
        }
        return best;
    }

    private double computeValleyPenalty(double px, double py) {
        if (contour.ridges.size() < 2) return 0;
        double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE;
        for (ContinentContour.Ridge r : contour.ridges) {
            double d = distToRidge(px, py, r.points);
            if (d < d1) { d2 = d1; d1 = d; }
            else if (d < d2) d2 = d;
        }
        double sigma = contour.radius * 0.10;
        return Math.exp(-d1 * d1 / (2 * sigma * sigma))
             * Math.exp(-d2 * d2 / (2 * sigma * sigma)) * 0.30;
    }

    private double distToRidge(double px, double py, List<ContinentContour.Pt> pts) {
        double minD = Double.MAX_VALUE;
        for (int i = 0; i < pts.size() - 1; i++) {
            var a = pts.get(i); var b = pts.get(i + 1);
            double dx = b.x - a.x, dy = b.y - a.y;
            double len2 = dx * dx + dy * dy;
            if (len2 < 0.001) {
                double d = Math.sqrt((px - a.x) * (px - a.x) + (py - a.y) * (py - a.y));
                if (d < minD) minD = d;
            } else {
                double t = Math.max(0, Math.min(1,
                    ((px - a.x) * dx + (py - a.y) * dy) / len2));
                double cx = a.x + t * dx, cy = a.y + t * dy;
                double d = Math.sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy));
                if (d < minD) minD = d;
            }
        }
        return minD;
    }

    private String classify(double height, double px, double py) {
        double moisture = noise.noise2(px * 0.02 + 500, py * 0.02 + 500);
        if (height > 0.68) return "mountain";
        if (height > 0.48) return "hills";
        if (height > 0.22) return "plains";
        if (height > 0.10) return moisture > 0.1 ? "forest" : "plains";
        return moisture > 0.2 ? "swamp" : "plains";
    }

    private static String terrainColor(String t) {
        return switch (t) {
            case "mountain" -> "#808080";
            case "hills"    -> "#BDB76B";
            case "forest"   -> "#228B22";
            case "plains"   -> "#6CC261";
            case "swamp"    -> "#556B2F";
            default         -> "#6CC261";
        };
    }

    // ═══════════════════════════════════════════════════════
    //  Embedded SimplexNoise (same as MapGenerator's)
    // ═══════════════════════════════════════════════════════

    static class SimplexNoise {
        private final long seed;
        SimplexNoise(long seed) { this.seed = seed; }

        double noise2(double x, double y) {
            int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
            double xf = x - xi, yf = y - yi;
            double n00 = dotGrid(xi, yi, xf, yf);
            double n10 = dotGrid(xi + 1, yi, xf - 1, yf);
            double n01 = dotGrid(xi, yi + 1, xf, yf - 1);
            double n11 = dotGrid(xi + 1, yi + 1, xf - 1, yf - 1);
            double u = smooth(xf), v = smooth(yf);
            return lerp(lerp(n00, n10, u), lerp(n01, n11, u), v);
        }

        private double dotGrid(int ix, int iy, double dx, double dy) {
            long h = hash(ix, iy);
            double angle = (h & 0xFFFF) * (2.0 * Math.PI / 65536.0);
            return Math.cos(angle) * dx + Math.sin(angle) * dy;
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
}

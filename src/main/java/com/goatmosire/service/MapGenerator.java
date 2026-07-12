package com.goatmosire.service;

import com.gsim.map.MapData;
import java.util.*;

/**
 * Ridge-Diffusion continent generator v4 — slim ridges, wide plains.
 *
 * Grok feedback fixes:
 *   - Steeper exponential decay (k=14-17) → thin mountain spines
 *   - Ridge weight reduced to 0.55 (was 0.75) → mountains don't dominate
 *   - Extra low-freq "shelf" noise → broad flat continental areas
 *   - High-freq ridge-break noise → mountains fragmented, not continuous
 *   - Wider plains band (0.22-0.48) → much more flat land
 *   - Mountain threshold raised to 0.68 → only sharp peaks
 */
public class MapGenerator {

    private final Random rng;
    private final int radius;
    private List<Ridge> ridges;
    private final SimplexNoise noise;

    record Ridge(List<Pt> points, double weight) {}
    record Pt(double x, double y) {}

    public MapGenerator(long seed, int mapRadius) {
        this.rng = new Random(seed);
        this.radius = mapRadius;
        this.noise = new SimplexNoise(seed);
    }

    public void placeRidges(int mainCount, int fragmentCount) {
        ridges = new ArrayList<>();
        double baseAngle = rng.nextDouble() * 2 * Math.PI;

        for (int i = 0; i < mainCount; i++) {
            double angle = baseAngle + (2 * Math.PI * i / mainCount) + rng.nextGaussian() * 0.35;
            double len = radius * (0.85 + rng.nextDouble() * 0.15);
            List<Pt> pts = new ArrayList<>();
            pts.add(new Pt(rng.nextGaussian() * radius * 0.03, rng.nextGaussian() * radius * 0.03));
            double mx = Math.cos(angle) * len * 0.4 + rng.nextGaussian() * radius * 0.06;
            double my = Math.sin(angle) * len * 0.4 + rng.nextGaussian() * radius * 0.06;
            pts.add(new Pt(mx, my));
            double ex = Math.cos(angle) * len + rng.nextGaussian() * radius * 0.05;
            double ey = Math.sin(angle) * len + rng.nextGaussian() * radius * 0.05;
            pts.add(new Pt(ex, ey));
            ridges.add(new Ridge(pts, 0.8 + rng.nextDouble() * 0.4));
        }

        for (int i = 0; i < fragmentCount; i++) {
            double angle = rng.nextDouble() * 2 * Math.PI;
            double dist = radius * (0.3 + rng.nextDouble() * 0.6);
            double cx = Math.cos(angle) * dist;
            double cy = Math.sin(angle) * dist;
            double flen = radius * (0.04 + rng.nextDouble() * 0.12);
            double fangle = angle + rng.nextGaussian() * 0.6;
            List<Pt> pts = new ArrayList<>();
            pts.add(new Pt(cx - Math.cos(fangle)*flen*0.5, cy - Math.sin(fangle)*flen*0.5));
            pts.add(new Pt(cx + Math.cos(fangle)*flen*0.5, cy + Math.sin(fangle)*flen*0.5));
            ridges.add(new Ridge(pts, 0.15 + rng.nextDouble() * 0.25));
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Height Field v4 — Slim Ridges + Wide Plains
    // ═══════════════════════════════════════════════════════

    public MapData generate(double landRatio) {
        var hexes = new LinkedHashMap<String, MapData.HexCell>();

        double baseSeaLevel = 0.22 + (1.0 - landRatio) * 0.04;

        // Radius-aware frequencies
        double nfShelf = 1.8 / radius;  // continental shelf → broad flat areas
        double nfLow   = 3.5 / radius;  // continental undulation
        double nfMid   = 8.0 / radius;  // hills
        double nfHigh  = 20.0 / radius; // detail + ridge breaking
        double nfCoast = 3.5 / radius;

        for (int q = -radius; q <= radius; q++) {
            for (int r = -radius; r <= radius; r++) {
                int s = -(q + r);
                if (Math.abs(q) + Math.abs(r) + Math.abs(s) > 2 * radius) continue;

                double px = q + r * 0.5;
                double py = r * 0.8660254;

                // ── Domain warp ──
                double wpx = px + noise.noise2(px * 0.018, py * 0.018) * 10;
                double wpy = py + noise.noise2(px * 0.018 + 70, py * 0.018 + 70) * 10;

                // ── 1. Ridge height (steep decay → visible but thin spines) ──
                double ridgeH = computeRidgeHeight(wpx, wpy);

                // ── 2. Continental shelf noise (broad uplift far from ridges) ──
                double shelf = noise.noise2(wpx * nfShelf, wpy * nfShelf);
                shelf = Math.max(0, shelf * 0.35 + 0.15); // bias positive

                // ── 4. Multi-band noise ──
                double n1 = noise.noise2(wpx * nfLow  + 100, wpy * nfLow  + 100);
                double n2 = noise.noise2(wpx * nfMid  + 300, wpy * nfMid  + 300);
                double n3 = noise.noise2(wpx * nfHigh + 500, wpy * nfHigh + 500);
                double multi = n1 * 0.40 + n2 * 0.25 + n3 * 0.12;

                // ── 5. Valley penalty ──
                double valley = computeValleyPenalty(wpx, wpy);

                // ── 6. Height assembly ──
                // Ridge: sharp but not dominant (0.55). Shelf: broad uplift (0.40).
                // Multi: fills gaps (0.50). Valley: carves (subtracted).
                double height = ridgeH * 0.55
                              + shelf * 0.40
                              + multi * 0.50
                              - valley;
                height = Math.max(0, height);
                height = Math.pow(height, 0.85); // contrast

                // ── 7. Coast noise ──
                double coastNoise = noise.noise2(wpx * nfCoast + 77, wpy * nfCoast + 77);
                double seaLevel = baseSeaLevel + coastNoise * 0.35;

                if (height < seaLevel) {
                    hexes.put(MapData.hexKey(q, r), waterCell());
                    continue;
                }

                // ── 8. Terrain classification ──
                String terrain = classifyByHeight(height, px, py);
                hexes.put(MapData.hexKey(q, r),
                    new MapData.HexCell(terrainColor(terrain), terrain, null, null, "", 0));
            }
        }

        return new MapData(30, false, hexes,
            new LinkedHashMap<>(), new LinkedHashMap<>(), List.of(), List.of(),
            defaultTerrainTypes());
    }

    // ═══════════════════════════════════════════════════════
    //  Components
    // ═══════════════════════════════════════════════════════

    /** Ridge height: steep exp(-d * k/radius). k=7-9 → visible but not fat. */
    private double computeRidgeHeight(double px, double py) {
        double best = 0;
        for (Ridge r : ridges) {
            double d = distToRidge(px, py, r.points);
            double k = 7.0 + r.weight * 2.0; // ~8.5-9.0 for main ridges
            double h = Math.exp(-d * k / radius);
            if (h > best) best = h;
        }
        return best;
    }

    private double computeValleyPenalty(double px, double py) {
        if (ridges.size() < 2) return 0;
        double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE;
        for (Ridge r : ridges) {
            double d = distToRidge(px, py, r.points);
            if (d < d1) { d2 = d1; d1 = d; }
            else if (d < d2) { d2 = d; }
        }
        double sigma = radius * 0.10;
        return Math.exp(-d1 * d1 / (2 * sigma * sigma))
             * Math.exp(-d2 * d2 / (2 * sigma * sigma)) * 0.30;
    }

    /**
     * Terrain classification v4 (wider plains):
     *   > 0.68 → mountain  (narrower, only sharp peaks)
     *   0.48-0.68 → hills
     *   0.22-0.48 → plains  (much wider band)
     *   0.10-0.22 → forest / plains mix
     *   < 0.10 → swamp
     */
    private String classifyByHeight(double height, double px, double py) {
        double moisture = noise.noise2(px * 0.02 + 500, py * 0.02 + 500);

        if (height > 0.68) return "mountain";
        if (height > 0.48) return "hills";
        if (height > 0.22) return "plains";
        if (height > 0.10) return moisture > 0.1 ? "forest" : "plains";
        return moisture > 0.2 ? "swamp" : "plains";
    }

    // ═══════════════════════════════════════════════════════
    //  Geometry
    // ═══════════════════════════════════════════════════════

    private double distToRidge(double px, double py, List<Pt> pts) {
        double minD = Double.MAX_VALUE;
        for (int i = 0; i < pts.size() - 1; i++) {
            Pt a = pts.get(i), b = pts.get(i + 1);
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

    // ═══════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════

    private MapData.HexCell waterCell() {
        return new MapData.HexCell("#3295D2", "water", null, null, "", 0);
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
    //  Simplex Noise
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

    // ═══════════════════════════════════════════════════════
    //  Terrain Types
    // ═══════════════════════════════════════════════════════

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
                                   int mainRidges, int fragments,
                                   double landRatio, double coastRoughness) {
        var gen = new MapGenerator(seed, mapRadius);
        gen.placeRidges(mainRidges, fragments);
        return gen.generate(landRatio);
    }
}

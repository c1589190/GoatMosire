package com.goatmosire.service;

import com.gsim.map.MapData;
import java.util.*;

/**
 * Ridge-Diffusion continent generator with continuous height field.
 *
 * Height formula (per hex):
 *   height = ridgeElevation × 0.45        // Gaussian bell around ridge lines
 *          + octave1 × 0.25               // low-freq continental undulation
 *          + octave2 × 0.18               // mid-freq hill folding
 *          + octave3 × 0.12               // high-freq surface detail
 *          - valleyPenalty                // depression between neighboring ridges
 *          + domainWarp                   // coordinate distortion for natural curves
 *
 * Sea level is noise-modulated; terrain classified by final height thresholds.
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

    /** Generate ridge lines radiating from center */
    public void placeRidges(int mainCount, int fragmentCount) {
        ridges = new ArrayList<>();
        double baseAngle = rng.nextDouble() * 2 * Math.PI;

        // ── Main ridges: long sweeping curves across the map ──
        for (int i = 0; i < mainCount; i++) {
            double angle = baseAngle + (2 * Math.PI * i / mainCount) + rng.nextGaussian() * 0.4;
            double len = radius * (0.85 + rng.nextDouble() * 0.15);
            List<Pt> pts = new ArrayList<>();
            // Start near center
            pts.add(new Pt(rng.nextGaussian() * radius * 0.04, rng.nextGaussian() * radius * 0.04));
            // Mid point
            double mx = Math.cos(angle) * len * 0.4 + rng.nextGaussian() * radius * 0.08;
            double my = Math.sin(angle) * len * 0.4 + rng.nextGaussian() * radius * 0.08;
            pts.add(new Pt(mx, my));
            // End near edge
            double ex = Math.cos(angle) * len + rng.nextGaussian() * radius * 0.06;
            double ey = Math.sin(angle) * len + rng.nextGaussian() * radius * 0.06;
            pts.add(new Pt(ex, ey));
            ridges.add(new Ridge(pts, 0.8 + rng.nextDouble() * 0.4));
        }

        // ── Fragment ridges (islands) ──
        for (int i = 0; i < fragmentCount; i++) {
            double angle = rng.nextDouble() * 2 * Math.PI;
            double dist = radius * (0.3 + rng.nextDouble() * 0.6);
            double cx = Math.cos(angle) * dist;
            double cy = Math.sin(angle) * dist;
            double flen = radius * (0.04 + rng.nextDouble() * 0.12);
            double fangle = angle + rng.nextGaussian() * 0.6;
            List<Pt> pts = new ArrayList<>();
            pts.add(new Pt(cx - Math.cos(fangle) * flen * 0.5, cy - Math.sin(fangle) * flen * 0.5));
            pts.add(new Pt(cx + Math.cos(fangle) * flen * 0.5, cy + Math.sin(fangle) * flen * 0.5));
            ridges.add(new Ridge(pts, 0.15 + rng.nextDouble() * 0.25));
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Height Field Computation
    // ═══════════════════════════════════════════════════════

    public MapData generate(double landRatio) {
        var hexes = new LinkedHashMap<String, MapData.HexCell>();

        // Sea level: landRatio controls threshold
        double baseSeaLevel = 0.10 + (1.0 - landRatio) * 0.05; // ~0.13 at landRatio=0.4

        for (int q = -radius; q <= radius; q++) {
            for (int r = -radius; r <= radius; r++) {
                int s = -(q + r);
                if (Math.abs(q) + Math.abs(r) + Math.abs(s) > 2 * radius) continue;

                double px = q + r * 0.5;
                double py = r * 0.8660254;

                // ── Domain warping (curves the ridge lines naturally) ──
                double warpX = noise.noise2(px * 0.006 + 50, py * 0.006 + 50) * radius * 0.08;
                double warpY = noise.noise2(px * 0.006 + 200, py * 0.006 + 200) * radius * 0.08;
                double spx = px + warpX;
                double spy = py + warpY;

                // ── 1. Ridge elevation (Gaussian bell from nearest ridge) ──
                double[] ridgeInfo = ridgeElevation(spx, spy);
                double ridgeH = ridgeInfo[0];
                double ridgeDist = ridgeInfo[1];
                double nearestWeight = ridgeInfo[2];

                // ── 2. Multi-octave noise ──
                double n1 = noise.noise2(spx * 0.005, spy * 0.005);      // continental
                double n2 = noise.noise2(spx * 0.018 + 100, spy * 0.018 + 100); // hills
                double n3 = noise.noise2(spx * 0.055 + 300, spy * 0.055 + 300); // detail

                // ── 3. Valley penalty (depression between close ridges) ──
                double valleyPenalty = computeValleyPenalty(spx, spy);

                // ── 4. Combine into final height ──
                double height = ridgeH * 0.45
                              + n1 * 0.25
                              + n2 * 0.18
                              + n3 * 0.12
                              - valleyPenalty;

                // Clamp to 0..1
                height = Math.max(0, Math.min(1, height));

                // ── 5. Modulated sea level ──
                double coastNoise = noise.noise2(px * 0.01 + 77, py * 0.01 + 77);
                double seaLevel = baseSeaLevel + coastNoise * 0.06;

                // ── 6. Land/water decision ──
                if (height < seaLevel) {
                    hexes.put(MapData.hexKey(q, r), waterCell());
                    continue;
                }

                // ── 7. Terrain classification by height ──
                String terrain = classifyByHeight(height, ridgeDist, nearestWeight, px, py);
                hexes.put(MapData.hexKey(q, r),
                    new MapData.HexCell(terrainColor(terrain), terrain, null, null, "", 0));
            }
        }

        return new MapData(30, false, hexes,
            new LinkedHashMap<>(), new LinkedHashMap<>(), List.of(), List.of(),
            defaultTerrainTypes());
    }

    // ═══════════════════════════════════════════════════════
    //  Component Functions
    // ═══════════════════════════════════════════════════════

    /**
     * Ridge elevation: Gaussian bell centered on nearest ridge.
     * Returns [elevation, distanceToRidge, ridgeWeight].
     * Multiple ridges combine via max() — ridges stay independent spines.
     */
    private double[] ridgeElevation(double px, double py) {
        double bestElev = 0, bestDist = Double.MAX_VALUE, bestWeight = 0;

        for (Ridge r : ridges) {
            double d = distToRidge(px, py, r.points);
            // Sigma: wider ridges (higher weight) have broader mountains
            double sigma = r.weight * radius * 0.12;  // wider coverage
            double elev = Math.exp(-(d * d) / (2 * sigma * sigma));
            if (elev > bestElev) {
                bestElev = elev;
                bestDist = d;
                bestWeight = r.weight;
            }
        }
        return new double[]{bestElev, bestDist, bestWeight};
    }

    /**
     * Valley penalty: if a point is close to TWO ridges, depress the height.
     * This creates natural valleys between mountain ranges.
     */
    private double computeValleyPenalty(double px, double py) {
        if (ridges.size() < 2) return 0;

        // Find two closest ridge distances
        double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE;
        for (Ridge r : ridges) {
            double d = distToRidge(px, py, r.points);
            if (d < d1) { d2 = d1; d1 = d; }
            else if (d < d2) { d2 = d; }
        }

        // Penalty proportional to how close BOTH ridges are
        double sigma = radius * 0.12;
        double nearBoth = Math.exp(-d1 * d1 / (2 * sigma * sigma))
                        * Math.exp(-d2 * d2 / (2 * sigma * sigma));
        return nearBoth * 0.35;
    }

    /**
     * Terrain classification based on final height (not ridge distance).
     * Uses moisture noise for forest distribution.
     */
    private String classifyByHeight(double height, double ridgeDist, double ridgeWeight, double px, double py) {
        double moisture = noise.noise2(px * 0.02 + 500, py * 0.02 + 500);

        if (height > 0.72) return "mountain";
        if (height > 0.52) return "hills";     // highlands → hills
        if (height > 0.32) return moisture > -0.1 ? "forest" : "plains";
        if (height > 0.12) return "plains";
        // Low coastal: swamp near water edge
        return moisture > 0.2 ? "swamp" : "plains";
    }

    // ═══════════════════════════════════════════════════════
    //  Geometry Helpers
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
    //  Terrain Helpers
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
    //  Simplex Noise (embedded, no external deps)
    // ═══════════════════════════════════════════════════════

    static class SimplexNoise {
        private final long seed;

        SimplexNoise(long seed) { this.seed = seed; }

        /** Returns value in roughly [-1, 1] range */
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

    // ═══════════════════════════════════════════════════════
    //  Public Factory
    // ═══════════════════════════════════════════════════════

    public static MapData generate(String worldId, long seed, int mapRadius,
                                   int mainRidges, int fragments,
                                   double landRatio, double coastRoughness) {
        var gen = new MapGenerator(seed, mapRadius);
        gen.placeRidges(mainRidges, fragments);
        return gen.generate(landRatio);
    }
}

package com.goatmosire.service;

import com.gsim.map.MapData;
import java.util.*;

/**
 * MapGenerator v5.3 — Subtropical Local Continent.
 *
 * <p>Directional orogenic ridges on top of a low-frequency continent mask.
 * Three-layer height: continent base → ridges → terrain texture.
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

    // ═══════════════════════════════════════════════════════
    //  Ridge Placement v5.1 — Directional Orogenic Ranges
    // ═══════════════════════════════════════════════════════

    /**
     * 1–2 main ranges with a clear primary direction offset from centre,
     * parallel/oblique secondary ranges, and a few scattered fragments.
     */
    public void placeRidges(int mainCount, int fragmentCount) {
        ridges = new ArrayList<>();
        double mainAngle = rng.nextDouble() * Math.PI;           // 0–180°

        // ── 主山脉 (1–2 条) ──
        int actualMain = Math.max(1, Math.min(mainCount, 2));
        for (int i = 0; i < actualMain; i++) {
            double angle = mainAngle + (i == 0 ? 0
                : (rng.nextDouble() * 0.6 + 0.4) * (rng.nextBoolean() ? 1 : -1));
            double len = radius * (1.4 + rng.nextDouble() * 0.4);

            // 偏离中心，垂直主方向偏移
            double perpAngle = angle + Math.PI / 2;
            double startOffset = radius * (0.30 + rng.nextDouble() * 0.25);
            double sx = Math.cos(perpAngle) * startOffset + rng.nextGaussian() * radius * 0.03;
            double sy = Math.sin(perpAngle) * startOffset + rng.nextGaussian() * radius * 0.03;

            // 贝塞尔控制点 → 弯曲
            double curveAmp = rng.nextDouble() * radius * 0.22 * (rng.nextBoolean() ? 1 : -1);

            List<Pt> pts = new ArrayList<>();
            pts.add(new Pt(
                sx - Math.cos(angle) * len * 0.48 + rng.nextGaussian() * radius * 0.02,
                sy - Math.sin(angle) * len * 0.48 + rng.nextGaussian() * radius * 0.02));
            pts.add(new Pt(
                sx + Math.cos(perpAngle) * curveAmp + rng.nextGaussian() * radius * 0.03,
                sy + Math.sin(perpAngle) * curveAmp + rng.nextGaussian() * radius * 0.03));
            pts.add(new Pt(
                sx + Math.cos(angle) * len * 0.52 + rng.nextGaussian() * radius * 0.04,
                sy + Math.sin(angle) * len * 0.52 + rng.nextGaussian() * radius * 0.04));
            ridges.add(new Ridge(pts, 0.80 + rng.nextDouble() * 0.20));
        }

        // ── 次级山脉 (大致平行/斜交主方向) ──
        int secondary = Math.max(2, fragmentCount / 2);
        for (int i = 0; i < secondary; i++) {
            double offsetAngle = mainAngle
                + (rng.nextDouble() * 0.45 + 0.15) * (rng.nextBoolean() ? 1 : -1);
            double perpDist = radius * (0.12 + rng.nextDouble() * 0.30)
                * (rng.nextBoolean() ? 1 : -1);
            double len = radius * (0.55 + rng.nextDouble() * 0.45);
            double sx = Math.cos(mainAngle) * radius * (0.05 + rng.nextDouble() * 0.25)
                       + Math.cos(mainAngle + Math.PI / 2) * perpDist;
            double sy = Math.sin(mainAngle) * radius * (0.05 + rng.nextDouble() * 0.25)
                       + Math.sin(mainAngle + Math.PI / 2) * perpDist;

            List<Pt> pts = new ArrayList<>();
            pts.add(new Pt(
                sx - Math.cos(offsetAngle) * len * 0.5 + rng.nextGaussian() * radius * 0.02,
                sy - Math.sin(offsetAngle) * len * 0.5 + rng.nextGaussian() * radius * 0.02));
            pts.add(new Pt(
                sx + Math.cos(offsetAngle) * len * 0.5 + rng.nextGaussian() * radius * 0.02,
                sy + Math.sin(offsetAngle) * len * 0.5 + rng.nextGaussian() * radius * 0.02));
            ridges.add(new Ridge(pts, 0.30 + rng.nextDouble() * 0.30));
        }

        // ── 碎片 (远离中心，低权重) ──
        int frags = fragmentCount - secondary;
        for (int i = 0; i < frags; i++) {
            double angle = rng.nextDouble() * 2 * Math.PI;
            double dist = radius * (0.45 + rng.nextDouble() * 0.40);
            double cx = Math.cos(angle) * dist;
            double cy = Math.sin(angle) * dist;
            double flen = radius * (0.04 + rng.nextDouble() * 0.08);
            double fangle = angle + rng.nextGaussian() * 0.5;
            List<Pt> pts = new ArrayList<>();
            pts.add(new Pt(cx - Math.cos(fangle) * flen * 0.5,
                           cy - Math.sin(fangle) * flen * 0.5));
            pts.add(new Pt(cx + Math.cos(fangle) * flen * 0.5,
                           cy + Math.sin(fangle) * flen * 0.5));
            ridges.add(new Ridge(pts, 0.08 + rng.nextDouble() * 0.14));
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Generation
    // ═══════════════════════════════════════════════════════

    public ContinentContour generateContour(double landRatio) {
        double baseSeaLevel = 0.18 + (1.0 - landRatio) * 0.06;
        double shelfFreq = 2.0 / radius;
        double lowFreq   = 4.0 / radius;
        double midFreq   = 9.0 / radius;
        double highFreq  = 22.0 / radius;
        double coastFreq = 4.0 / radius;
        List<ContinentContour.Ridge> contourRidges = new ArrayList<>();
        for (Ridge r : ridges) {
            List<ContinentContour.Pt> pts = new ArrayList<>();
            for (Pt p : r.points) pts.add(new ContinentContour.Pt(p.x, p.y));
            contourRidges.add(new ContinentContour.Ridge(pts, r.weight));
        }
        return new ContinentContour(
            rng.nextLong(), radius, landRatio, contourRidges,
            baseSeaLevel, shelfFreq, lowFreq, midFreq, highFreq, coastFreq);
    }

    /** Full hex map for the given land ratio. */
    public MapData generate(double landRatio) {
        if (ridges == null || ridges.isEmpty()) placeRidges(2, 5);

        LinkedHashMap<String, MapData.TerrainType> terrainTypes = defaultTerrainTypes();
        Map<String, MapData.HexCell> hexes = new LinkedHashMap<>();

        // 海平面独立于高度计算：landRatio 高 → 海平面降低 → 更多陆地
        double seaLevel = 0.42 - landRatio * 0.32;  // landRatio 0.6 → seaLevel≈0.23

        for (int q = -radius; q <= radius; q++) {
            for (int r = -radius; r <= radius; r++) {
                int s = -q - r;
                if (Math.abs(q) > radius || Math.abs(r) > radius || Math.abs(s) > radius) continue;

                double[] pos = hexToWorld(q, r);
                double px = pos[0], py = pos[1];

                // 域扭曲
                double warpX = noise.noise2(px * 0.015 + 300, py * 0.015 + 300) * radius * 0.10;
                double warpY = noise.noise2(px * 0.015 + 700, py * 0.015 + 700) * radius * 0.10;

                double rawHeight = computeHeight(px + warpX, py + warpY);
                String terrain;
                if (rawHeight < seaLevel) {
                    terrain = "water";
                } else {
                    // 将 seaLevel 映射到分类的 0 基准
                    double normHeight = (rawHeight - seaLevel) / (1.0 - seaLevel);
                    terrain = classifyByHeight(normHeight, px, py);
                }

                if ("water".equals(terrain)) {
                    hexes.put(MapData.hexKey(q, r), waterCell());
                } else {
                    hexes.put(MapData.hexKey(q, r),
                        new MapData.HexCell(terrainColor(terrain), terrain, null, null, "", 0));
                }
            }
        }

        return new MapData(radius * 2, false, hexes, List.of(),
            Map.of(), Map.of(), List.of(), List.of(), terrainTypes);
    }

    // ═══════════════════════════════════════════════════════
    //  Height Computation v5.3 — Continent Base + Ridges
    // ═══════════════════════════════════════════════════════

    /**
     * Three-layer height:
     *   1. continent mask — ultra-low-freq, strong positive bias → large landmass
     *   2. ridges — orogenic spines on top of the continent
     *   3. terrain + detail — mid/high-freq relief for texture
     * Then compare against seaLevel; normalise the remainder for classification.
     */
    private double computeHeight(double px, double py) {
        double ridgeH    = computeRidgeHeight(px, py);
        double contFreq  = 1.2 / radius, shelfFreq = 2.5 / radius;
        double terrFreq  = 5.5 / radius, detailFreq = 13.0 / radius;

        // ① 低频大陆形状 — 最重要的基础层，强正偏置
        double continent = noise.noise2(px * contFreq, py * contFreq);
        continent = Math.max(0, continent * 0.60 + 0.45);    // ~0.15–0.81 范围，大部分 >0.3

        // ② 大陆架 — 补充低频波动，让海岸线不完全圆
        double shelf = noise.noise2(px * shelfFreq + 100, py * shelfFreq + 100) * 0.35 + 0.10;

        // ③ 中频地形起伏
        double terrain = noise.noise2(px * terrFreq + 200, py * terrFreq + 200) * 0.20;

        // ④ 高频细节
        double detail  = noise.noise2(px * detailFreq + 400, py * detailFreq + 400) * 0.06;

        double valley  = computeValleyPenalty(px, py);

        // 大陆基座 0.55 占主导  脊线 0.75 确保刺破  其余提供纹理
        return continent * 0.55 + ridgeH * 0.75 + shelf * 0.25
             + terrain * 0.20 + detail * 0.08 - valley * 0.10;
    }

    /** Ridge attenuation — k=8~11: visible but not fat. */
    private double computeRidgeHeight(double px, double py) {
        double best = 0;
        for (Ridge r : ridges) {
            double d = distToRidge(px, py, r.points);
            double k = 8.0 + r.weight * 3.5;   // main ~11, secondary ~9, fragment ~8
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
        double sigma = radius * 0.07;
        return Math.exp(-d1 * d1 / (2 * sigma * sigma))
             * Math.exp(-d2 * d2 / (2 * sigma * sigma)) * 0.15;
    }

    // ═══════════════════════════════════════════════════════
    //  Classification v5.2 — Normalised height (0=seaLevel, 1=peak)
    // ═══════════════════════════════════════════════════════

    /**
     * Height is already normalised: water is filtered out before this call.
     * <pre>
     *   mountain  > 0.65
     *   hills     0.48 – 0.65
     *   plains    0.36 – 0.48  (smaller area, mixed hill patches)
     *   lowland   0.16 – 0.36  (dominant, ~15% plains patches inside)
     *   swamp     < 0.16       (coastal / low-lying near water)
     * </pre>
     */
    private String classifyByHeight(double height, double px, double py) {
        double moisture = noise.noise2(px * 0.02 + 500, py * 0.02 + 500);

        if (height > 0.65) return "mountain";
        if (height > 0.48) {
            return (moisture + noise.noise2(px * 0.06, py * 0.06)) > 0.08 ? "hills" : "plains";
        }

        if (height > 0.36) {
            return (moisture + noise.noise2(px * 0.06, py * 0.06)) > 0.10 ? "hills" : "plains";
        }

        if (height > 0.16) {
            double patchNoise = noise.noise2(px * 0.05 + 600, py * 0.05 + 600);
            if (patchNoise > 0.44) return "plains";
            return "lowland";
        }

        return moisture > -0.15 ? "swamp" : "lowland";
    }

    // ═══════════════════════════════════════════════════════
    //  Geometry
    // ═══════════════════════════════════════════════════════

    private double distToRidge(double px, double py, List<Pt> pts) {
        double minD = Double.MAX_VALUE;
        for (int i = 0; i < pts.size() - 1; i++) {
            Pt a = pts.get(i), b = pts.get(i + 1);
            double dx = b.x - a.x, dy = b.y - a.y, len2 = dx * dx + dy * dy;
            if (len2 < 0.001) {
                double d = Math.sqrt((px - a.x) * (px - a.x) + (py - a.y) * (py - a.y));
                if (d < minD) minD = d;
            } else {
                double t = Math.max(0, Math.min(1,
                    ((px - a.x) * dx + (py - a.y) * dy) / len2));
                double d = Math.sqrt((px - a.x - t * dx) * (px - a.x - t * dx)
                                   + (py - a.y - t * dy) * (py - a.y - t * dy));
                if (d < minD) minD = d;
            }
        }
        return minD;
    }

    private static double[] hexToWorld(int q, int r) {
        return new double[]{
            (Math.sqrt(3) * q + Math.sqrt(3) / 2 * r) * 30.0,
            (3.0 / 2 * r) * 30.0
        };
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
            case "hills"    -> "#A0522D";
            case "plains"   -> "#C5B358";
            case "lowland"  -> "#5B8C3E";
            case "swamp"    -> "#556B2F";
            default         -> "#5B8C3E";
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
            double u = smooth(xf), v = smooth(yf);
            return lerp(
                lerp(dotGrid(xi, yi, xf, yf), dotGrid(xi + 1, yi, xf - 1, yf), u),
                lerp(dotGrid(xi, yi + 1, xf, yf - 1), dotGrid(xi + 1, yi + 1, xf - 1, yf - 1), u), v);
        }

        private double dotGrid(int ix, int iy, double dx, double dy) {
            double angle = (hash(ix, iy) & 0xFFFF) * (2.0 * Math.PI / 65536.0);
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

    static LinkedHashMap<String, MapData.TerrainType> defaultTerrainTypes() {
        var tt = new LinkedHashMap<String, MapData.TerrainType>();
        tt.put("water",    new MapData.TerrainType("水",   "#3295D2", 1, 0, 0, 99, "水域"));
        tt.put("lowland",  new MapData.TerrainType("低地", "#5B8C3E", 3, 1, 1, 1,  "低地"));
        tt.put("plains",   new MapData.TerrainType("平原", "#C5B358", 2, 2, 1, 1,  "平原/丘陵混合"));
        tt.put("forest",   new MapData.TerrainType("森林", "#228B22", 2, 1, 2, 2,  "森林 (兼容旧地图)"));
        tt.put("hills",    new MapData.TerrainType("丘陵", "#A0522D", 2, 1, 3, 2,  "丘陵"));
        tt.put("mountain", new MapData.TerrainType("山地", "#808080", 0, 2, 5, 3,  "山地"));
        tt.put("swamp",    new MapData.TerrainType("沼泽", "#556B2F", 2, 0, 1, 2,  "沼泽"));
        tt.put("desert",   new MapData.TerrainType("沙漠", "#DDC88D", 1, 2, 1, 2,  "沙漠"));
        tt.put("tundra",   new MapData.TerrainType("冻土", "#A8C4D8", 1, 1, 1, 2,  "冻土"));
        return tt;
    }

    // ═══════════════════════════════════════════════════════
    //  Static Factory
    // ═══════════════════════════════════════════════════════

    public static MapData generate(String worldId, long seed, int mapRadius,
                                   int mainRidges, int fragments,
                                   double landRatio, double coastRoughness) {
        var gen = new MapGenerator(seed, mapRadius);
        gen.placeRidges(mainRidges, fragments);
        return gen.generate(landRatio);
    }
}

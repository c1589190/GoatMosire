package com.goatmosire.service;

import com.gsim.map.MapData;
import java.util.*;

/**
 * MapGenerator v5 — Subtropical Local Continent.
 *
 * <p>Directional orogenic mountain ranges (not radial), wide lowland-dominant
 * terrain with embedded plains patches, and reduced ocean coverage.
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
    //  Ridge Placement v5 — Directional Orogenic Ranges
    // ═══════════════════════════════════════════════════════

    /**
     * Place ridges in a natural orogenic pattern:
     *   1–2 main ranges with a clear primary direction, offset from centre.
     *   3–4 secondary ranges roughly parallel or oblique to the mains.
     *   A few short fragments at random locations.
     *   No ridge starts near (0,0).
     */
    public void placeRidges(int mainCount, int fragmentCount) {
        ridges = new ArrayList<>();
        // 主方向：随机角度
        double mainAngle = rng.nextDouble() * Math.PI;  // 0–180°

        // ── 主山脉 (1–2 条) ──
        int actualMain = Math.max(1, Math.min(mainCount, 2));
        for (int i = 0; i < actualMain; i++) {
            double angle = mainAngle + (i == 0 ? 0 : (rng.nextDouble() * 0.6 + 0.4) * (rng.nextBoolean() ? 1 : -1));
            double len = radius * (1.4 + rng.nextDouble() * 0.4);
            // 起始点偏离中心 radius*0.3~0.6，与主方向垂直偏移
            double perpAngle = angle + Math.PI / 2;
            double startOffset = radius * (0.3 + rng.nextDouble() * 0.3);
            double sx = Math.cos(perpAngle) * startOffset + rng.nextGaussian() * radius * 0.03;
            double sy = Math.sin(perpAngle) * startOffset + rng.nextGaussian() * radius * 0.03;

            List<Pt> pts = new ArrayList<>();
            pts.add(new Pt(
                sx - Math.cos(angle) * len * 0.45 + rng.nextGaussian() * radius * 0.02,
                sy - Math.sin(angle) * len * 0.45 + rng.nextGaussian() * radius * 0.02));
            // 贝塞尔控制点：产生弯曲
            double curveAmp = rng.nextDouble() * radius * 0.25 * (rng.nextBoolean() ? 1 : -1);
            double ctrlX = sx + Math.cos(perpAngle) * curveAmp + rng.nextGaussian() * radius * 0.03;
            double ctrlY = sy + Math.sin(perpAngle) * curveAmp + rng.nextGaussian() * radius * 0.03;
            pts.add(new Pt(ctrlX, ctrlY));
            pts.add(new Pt(
                sx + Math.cos(angle) * len * 0.55 + rng.nextGaussian() * radius * 0.04,
                sy + Math.sin(angle) * len * 0.55 + rng.nextGaussian() * radius * 0.04));
            ridges.add(new Ridge(pts, 0.75 + rng.nextDouble() * 0.25));
        }

        // ── 次级山脉 (3–4 条，大致平行/斜交主方向) ──
        int secondary = Math.max(2, fragmentCount / 2);
        for (int i = 0; i < secondary; i++) {
            double offsetAngle = mainAngle + (rng.nextDouble() * 0.5 + 0.15) * (rng.nextBoolean() ? 1 : -1);
            double perpDist = radius * (0.15 + rng.nextDouble() * 0.35) * (rng.nextBoolean() ? 1 : -1);
            double len = radius * (0.6 + rng.nextDouble() * 0.5);
            double sx = Math.cos(mainAngle) * radius * (0.05 + rng.nextDouble() * 0.3)
                       + Math.cos(mainAngle + Math.PI / 2) * perpDist;
            double sy = Math.sin(mainAngle) * radius * (0.05 + rng.nextDouble() * 0.3)
                       + Math.sin(mainAngle + Math.PI / 2) * perpDist;

            List<Pt> pts = new ArrayList<>();
            pts.add(new Pt(
                sx - Math.cos(offsetAngle) * len * 0.5 + rng.nextGaussian() * radius * 0.02,
                sy - Math.sin(offsetAngle) * len * 0.5 + rng.nextGaussian() * radius * 0.02));
            pts.add(new Pt(
                sx + Math.cos(offsetAngle) * len * 0.5 + rng.nextGaussian() * radius * 0.02,
                sy + Math.sin(offsetAngle) * len * 0.5 + rng.nextGaussian() * radius * 0.02));
            ridges.add(new Ridge(pts, 0.3 + rng.nextDouble() * 0.35));
        }

        // ── 碎片 (远离中心，低权重) ──
        int frags = fragmentCount - secondary;
        for (int i = 0; i < frags; i++) {
            double angle = rng.nextDouble() * 2 * Math.PI;
            double dist = radius * (0.45 + rng.nextDouble() * 0.45);
            double cx = Math.cos(angle) * dist;
            double cy = Math.sin(angle) * dist;
            double flen = radius * (0.04 + rng.nextDouble() * 0.10);
            double fangle = angle + rng.nextGaussian() * 0.5;
            List<Pt> pts = new ArrayList<>();
            pts.add(new Pt(cx - Math.cos(fangle) * flen * 0.5, cy - Math.sin(fangle) * flen * 0.5));
            pts.add(new Pt(cx + Math.cos(fangle) * flen * 0.5, cy + Math.sin(fangle) * flen * 0.5));
            ridges.add(new Ridge(pts, 0.10 + rng.nextDouble() * 0.18));
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Height Field v5 — Multi-noise + Ridge Attenuation
    // ═══════════════════════════════════════════════════════

    /** Generate contour for external use (retained for API compat). */
    public ContinentContour generateContour(double landRatio) {
        double baseSeaLevel = 0.04 + (1.0 - landRatio) * 0.06;  // lower sea → more land
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
            baseSeaLevel, shelfFreq, lowFreq, midFreq, highFreq, coastFreq
        );
    }

    /** Materialize the full hex map for a given land ratio. */
    public MapData generate(double landRatio) {
        if (ridges == null || ridges.isEmpty()) {
            placeRidges(2, 5);
        }

        LinkedHashMap<String, MapData.TerrainType> terrainTypes = defaultTerrainTypes();
        Map<String, MapData.HexCell> hexes = new LinkedHashMap<>();
        double baseSeaLevel = 0.03 + (1.0 - landRatio) * 0.05;  // 降低海平面

        for (int q = -radius; q <= radius; q++) {
            for (int r = -radius; r <= radius; r++) {
                // 六边形距离：超出地图范围的跳过
                int s = -q - r;
                if (Math.abs(q) > radius || Math.abs(r) > radius || Math.abs(s) > radius) continue;

                double[] pos = hexToWorld(q, r);
                double px = pos[0], py = pos[1];

                // 域扭曲
                double warpX = noise.noise2(px * 0.015 + 300, py * 0.015 + 300) * radius * 0.12;
                double warpY = noise.noise2(px * 0.015 + 700, py * 0.015 + 700) * radius * 0.12;

                double height = computeHeight(px + warpX, py + warpY, baseSeaLevel, landRatio);
                String terrain = classifyByHeight(height, px, py);

                if ("water".equals(terrain)) {
                    hexes.put(MapData.hexKey(q, r), waterCell());
                } else {
                    String color = terrainColor(terrain);
                    hexes.put(MapData.hexKey(q, r),
                        new MapData.HexCell(color, terrain, null, null, "", 0));
                }
            }
        }

        return new MapData(radius * 2, false, hexes, List.of(),
            Map.of(), Map.of(), List.of(), List.of(), terrainTypes);
    }

    // ═══════════════════════════════════════════════════════
    //  Height Computation
    // ═══════════════════════════════════════════════════════

    /**
     * Composite height = ridgeHeight + shelfNoise + terrainNoise − valleyPenalty.
     * shelfNoise 低频正偏置抬升大陆架 → 扩大陆地。
     */
    private double computeHeight(double px, double py, double baseSeaLevel, double landRatio) {
        double ridgeH = computeRidgeHeight(px, py);
        double shelfFreq = 2.0 / radius;
        double terrainFreq = 4.5 / radius;
        double detailFreq = 10.0 / radius;

        // 大陆架噪声：低频 + 正偏置（0.3 → 整体抬高）
        double shelf = noise.noise2(px * shelfFreq + 100, py * shelfFreq + 100) * 0.5 + 0.30;

        // 地形噪声：中频波动
        double terrain = noise.noise2(px * terrainFreq + 200, py * terrainFreq + 200) * 0.35;

        // 细节噪声：高频小振幅
        double detail = noise.noise2(px * detailFreq + 400, py * detailFreq + 400) * 0.10;

        // 脊线高度 (0–1, 仅山脊附近显著)
        // 山谷惩罚：山脊间低洼
        double valley = computeValleyPenalty(px, py);

        // 陆地偏置：landRatio 越高，整体基线越高
        double landBias = (landRatio - 0.4) * 0.25;

        return ridgeH * 0.55 + shelf * 0.55 + terrain * 0.35 + detail * 0.15
             - valley * 0.15 - baseSeaLevel * 0.8 + landBias;
    }

    /** Sharp ridge attenuation — k=10~13 for thin spines. */
    private double computeRidgeHeight(double px, double py) {
        double best = 0;
        for (Ridge r : ridges) {
            double d = distToRidge(px, py, r.points);
            double k = 10.0 + r.weight * 3.0; // main ~12.5, secondary ~10.5, fragment ~9.5
            double h = Math.exp(-d * k / radius);
            if (h > best) best = h;
        }
        return best;
    }

    /** Penalty in valleys between ridges — reduced from v4. */
    private double computeValleyPenalty(double px, double py) {
        if (ridges.size() < 2) return 0;
        double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE;
        for (Ridge r : ridges) {
            double d = distToRidge(px, py, r.points);
            if (d < d1) { d2 = d1; d1 = d; }
            else if (d < d2) { d2 = d; }
        }
        double sigma = radius * 0.08;
        return Math.exp(-d1 * d1 / (2 * sigma * sigma))
             * Math.exp(-d2 * d2 / (2 * sigma * sigma)) * 0.20;
    }

    // ═══════════════════════════════════════════════════════
    //  Terrain Classification v5 — Lowland Dominant
    // ═══════════════════════════════════════════════════════

    /**
     * Classify height into terrain types. Thresholds tuned for subtropical
     * local continent with wide lowland and thin mountain ridges.
     */
    private String classifyByHeight(double height, double px, double py) {
        double moisture = noise.noise2(px * 0.02 + 500, py * 0.02 + 500);

        if (height > 0.70) return "mountain";
        if (height > 0.52) return "hills";

        // 较高平原：面积较小，掺入 hills 斑块
        if (height > 0.40) {
            return (moisture + noise.noise2(px * 0.06, py * 0.06)) > 0.15 ? "hills" : "plains";
        }

        // lowland 主导低地：内部随机 plains 斑块（~15-20% 概率）
        if (height > 0.14) {
            double patchNoise = noise.noise2(px * 0.05 + 600, py * 0.05 + 600);
            if (patchNoise > 0.42) return "plains";  // 随机小斑块
            return "lowland";
        }

        // 极低：沿海沼泽 或 水域
        if (height > 0.10) {
            return moisture > -0.2 ? "swamp" : "lowland";
        }
        return "water";
    }

    // ═══════════════════════════════════════════════════════
    //  Geometry
    // ═══════════════════════════════════════════════════════

    /** Point-to-segment distance for ridge proximity. */
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

    /** Axial hex coords → world position (flat-top hex). */
    private static double[] hexToWorld(int q, int r) {
        double x = (Math.sqrt(3) * q + Math.sqrt(3) / 2 * r) * 30.0;
        double y = (3.0 / 2 * r) * 30.0;
        return new double[]{x, y};
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
    //  Terrain Types — v5 adds lowland, retains forest for compat
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
    //  Static Factory — retained for API compat
    // ═══════════════════════════════════════════════════════

    public static MapData generate(String worldId, long seed, int mapRadius,
                                   int mainRidges, int fragments,
                                   double landRatio, double coastRoughness) {
        var gen = new MapGenerator(seed, mapRadius);
        gen.placeRidges(mainRidges, fragments);
        return gen.generate(landRatio);
    }
}

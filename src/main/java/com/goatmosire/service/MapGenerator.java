package com.goatmosire.service;

import com.gsim.map.MapData;
import java.util.*;

/**
 * MapGenerator v5 — Directional Orogen + Lowland-Dominant Terrain.
 *
 * <p>基于 v4 Ridge-Diffusion 的最小改动优化：
 *   - 脊线从放射状改为定向造山带（主方向 + 次级平行 + 碎片）
 *   - forest 改名为 lowland（低地），大幅扩大面积
 *   - plains 面积缩小，作为 lowland 内部噪声斑块出现
 *   - 海平面微调以减少海洋
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

    /** v5: Directional orogenic ridges — main range + parallel secondaries + fragments. */
    public void placeRidges(int mainCount, int fragmentCount) {
        ridges = new ArrayList<>();
        double mainAngle = rng.nextDouble() * Math.PI;  // 主方向 0–180°

        // ── 主山脉 (1–2 条，偏离中心，沿主方向伸展) ──
        int actualMain = Math.max(1, Math.min(mainCount, 2));
        for (int i = 0; i < actualMain; i++) {
            double angle = mainAngle + (i == 0 ? 0
                : (rng.nextDouble() * 0.5 + 0.3) * (rng.nextBoolean() ? 1 : -1));
            double len = radius * (1.3 + rng.nextDouble() * 0.4);
            // 偏离中心，垂直主方向偏移
            double perpAngle = angle + Math.PI / 2;
            double startOff = radius * (0.20 + rng.nextDouble() * 0.30);
            double sx = Math.cos(perpAngle) * startOff + rng.nextGaussian() * radius * 0.03;
            double sy = Math.sin(perpAngle) * startOff + rng.nextGaussian() * radius * 0.03;
            // 贝塞尔控制点
            double curve = rng.nextDouble() * radius * 0.18 * (rng.nextBoolean() ? 1 : -1);

            List<Pt> pts = new ArrayList<>();
            pts.add(new Pt(
                sx - Math.cos(angle) * len * 0.48 + rng.nextGaussian() * radius * 0.02,
                sy - Math.sin(angle) * len * 0.48 + rng.nextGaussian() * radius * 0.02));
            pts.add(new Pt(sx + Math.cos(perpAngle) * curve, sy + Math.sin(perpAngle) * curve));
            pts.add(new Pt(
                sx + Math.cos(angle) * len * 0.52 + rng.nextGaussian() * radius * 0.03,
                sy + Math.sin(angle) * len * 0.52 + rng.nextGaussian() * radius * 0.03));
            ridges.add(new Ridge(pts, 0.75 + rng.nextDouble() * 0.25));
        }

        // ── 次级山脉 (大致平行/斜交主方向) ──
        int secondary = Math.max(2, fragmentCount / 2);
        for (int i = 0; i < secondary; i++) {
            double offAngle = mainAngle
                + (rng.nextDouble() * 0.4 + 0.12) * (rng.nextBoolean() ? 1 : -1);
            double perpDist = radius * (0.10 + rng.nextDouble() * 0.28) * (rng.nextBoolean() ? 1 : -1);
            double len = radius * (0.50 + rng.nextDouble() * 0.45);
            double sx = Math.cos(mainAngle) * radius * (0.05 + rng.nextDouble() * 0.22)
                       + Math.cos(mainAngle + Math.PI / 2) * perpDist;
            double sy = Math.sin(mainAngle) * radius * (0.05 + rng.nextDouble() * 0.22)
                       + Math.sin(mainAngle + Math.PI / 2) * perpDist;
            List<Pt> pts = new ArrayList<>();
            pts.add(new Pt(
                sx - Math.cos(offAngle) * len * 0.5 + rng.nextGaussian() * radius * 0.02,
                sy - Math.sin(offAngle) * len * 0.5 + rng.nextGaussian() * radius * 0.02));
            pts.add(new Pt(
                sx + Math.cos(offAngle) * len * 0.5 + rng.nextGaussian() * radius * 0.02,
                sy + Math.sin(offAngle) * len * 0.5 + rng.nextGaussian() * radius * 0.02));
            ridges.add(new Ridge(pts, 0.25 + rng.nextDouble() * 0.30));
        }

        // ── 碎片 (随机位置，低权重) ──
        int frags = fragmentCount - secondary;
        for (int i = 0; i < frags; i++) {
            double angle = rng.nextDouble() * 2 * Math.PI;
            double dist = radius * (0.35 + rng.nextDouble() * 0.50);
            double cx = Math.cos(angle) * dist;
            double cy = Math.sin(angle) * dist;
            double flen = radius * (0.04 + rng.nextDouble() * 0.08);
            double fangle = angle + rng.nextGaussian() * 0.5;
            List<Pt> pts = new ArrayList<>();
            pts.add(new Pt(cx - Math.cos(fangle) * flen * 0.5, cy - Math.sin(fangle) * flen * 0.5));
            pts.add(new Pt(cx + Math.cos(fangle) * flen * 0.5, cy + Math.sin(fangle) * flen * 0.5));
            ridges.add(new Ridge(pts, 0.10 + rng.nextDouble() * 0.15));
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Height Field v4 — Slim Ridges + Wide Plains
    // ═══════════════════════════════════════════════════════

    /** Generate compact continent contour — v5 reduced baseSeaLevel for more land. */
    public ContinentContour generateContour(double landRatio) {
        double baseSeaLevel = 0.18 + (1.0 - landRatio) * 0.05;  // 降低以减少海洋
        double shelfFreq = 1.8 / radius;
        double lowFreq   = 3.5 / radius;
        double midFreq   = 8.0 / radius;
        double highFreq  = 20.0 / radius;
        double coastFreq = 3.5 / radius;

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

    /** Materialize full map from contour. */
    public MapData generate(double landRatio) {
        ContinentContour contour = generateContour(landRatio);
        ContourQueryEngine engine = new ContourQueryEngine(contour);
        return engine.materialize(-radius, radius, -radius, radius);
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
     * Terrain classification v5 — lowland-dominant:
     *   > 0.68 → mountain
     *   0.48-0.68 → hills
     *   0.35-0.48 → plains (缩小，掺 hills 斑块)
     *   0.12-0.35 → lowland (扩大，内部随机 plains 斑块 ~12%)
     *   < 0.12 → swamp
     */
    private String classifyByHeight(double height, double px, double py) {
        double moisture = noise.noise2(px * 0.02 + 500, py * 0.02 + 500);

        if (height > 0.68) return "mountain";
        if (height > 0.48) return "hills";
        if (height > 0.35) return moisture > 0.05 ? "hills" : "plains";
        if (height > 0.12) {
            double patch = noise.noise2(px * 0.05 + 600, py * 0.05 + 600);
            if (patch > 0.44) return "plains";  // ~12% 斑块
            return "lowland";
        }
        return moisture > 0.15 ? "swamp" : "lowland";
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
            case "hills"    -> "#A0522D";
            case "lowland"  -> "#5B8C3E";
            case "plains"   -> "#C5B358";
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

    /** Static convenience — generate a full map in one call. */
    public static MapData generate(String worldId, long seed, int mapRadius,
                                   int mainRidges, int fragments,
                                   double landRatio, double coastRoughness) {
        var gen = new MapGenerator(seed, mapRadius);
        gen.placeRidges(mainRidges, fragments);
        return gen.generate(landRatio);
    }
}

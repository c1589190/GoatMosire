package com.goatmosire.service;

import com.goatmosire.map.MapData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

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

    record Ridge(List<Pt> points, double weight) {}

    record Pt(double x, double y) {}

    /**
     * Constructs a map generator with the given seed and radius.
     * @param seed random seed for reproducibility
     * @param mapRadius hex grid radius
     */
    public MapGenerator(long seed, int mapRadius) {
        this.rng = new Random(seed);
        this.radius = mapRadius;
        this.ridges = new ArrayList<>();
    }

    /**
     * v5: Directional orogenic ridges — main range + parallel secondaries + fragments.
     * @param mainCount number of main ridge chains (actual count clamped to 1-2)
     * @param fragmentCount number of fragmentary ridges (secondary + random fragments)
     */
    public void placeRidges(int mainCount, int fragmentCount) {
        ridges = new ArrayList<>();
        double mainAngle = rng.nextDouble() * Math.PI; // 主方向 0–180°

        // ── 主山脉 (1–2 条，偏离中心，沿主方向伸展) ──
        int actualMain = Math.max(1, Math.min(mainCount, 2));
        for (int i = 0; i < actualMain; i++) {
            double angle = mainAngle + (i == 0 ? 0 : (rng.nextDouble() * 0.5 + 0.3) * (rng.nextBoolean() ? 1 : -1));
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
            double offAngle = mainAngle + (rng.nextDouble() * 0.4 + 0.12) * (rng.nextBoolean() ? 1 : -1);
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

    /**
     * Generate a compact continent contour from the placed ridges.
     * @param landRatio target land-to-total ratio (0.0-1.0)
     * @return a ContinentContour ready for materialization
     */
    public ContinentContour generateContour(double landRatio) {
        double baseSeaLevel = 0.18 + (1.0 - landRatio) * 0.05; // 降低以减少海洋
        double shelfFreq = 1.8 / radius;
        double lowFreq = 3.5 / radius;
        double midFreq = 8.0 / radius;
        double highFreq = 20.0 / radius;
        double coastFreq = 3.5 / radius;

        List<ContinentContour.Ridge> contourRidges = new ArrayList<>();
        for (Ridge r : ridges) {
            List<ContinentContour.Pt> pts = new ArrayList<>();
            for (Pt p : r.points) {
                pts.add(new ContinentContour.Pt(p.x, p.y));
            }
            contourRidges.add(new ContinentContour.Ridge(pts, r.weight));
        }

        return new ContinentContour(
                rng.nextLong(),
                radius,
                landRatio,
                contourRidges,
                baseSeaLevel,
                shelfFreq,
                lowFreq,
                midFreq,
                highFreq,
                coastFreq);
    }

    /**
     * Materialize a full MapData from the current ridge configuration and land ratio.
     * @param landRatio target land-to-total ratio
     * @return fully populated MapData with hexes and terrain types
     */
    public MapData generate(double landRatio) {
        ContinentContour contour = generateContour(landRatio);
        ContourQueryEngine engine = new ContourQueryEngine(contour);
        return engine.materialize(-radius, radius, -radius, radius);
    }

    // ═══════════════════════════════════════════════════════
    //  Terrain Types
    // ═══════════════════════════════════════════════════════

    /**
     * Returns the default terrain type definitions used by generated maps.
     * @return a LinkedHashMap of default TerrainType definitions used by generated maps
     */
    static LinkedHashMap<String, MapData.TerrainType> defaultTerrainTypes() {
        var tt = new LinkedHashMap<String, MapData.TerrainType>();
        tt.put("water", new MapData.TerrainType("水域", "#3295D2", 1, 0, 0, 99, "海洋/湖泊"));
        tt.put("lowland", new MapData.TerrainType("低地", "#5B8C3E", 3, 1, 1, 1, "沿海低地，向内陆过渡"));
        tt.put("hills", new MapData.TerrainType("丘陵", "#A0522D", 2, 1, 3, 2, "低地与山区的过渡带"));
        tt.put("plains", new MapData.TerrainType("山区", "#B8A88A", 2, 2, 1, 1, "内陆高原/山区，高山峰簇散布其间"));
        tt.put("mountain", new MapData.TerrainType("高山", "#6B6B6B", 0, 2, 5, 3, "高山峰簇，嵌入山区内部"));
        tt.put("forest", new MapData.TerrainType("森林", "#228B22", 2, 1, 2, 2, "森林 (兼容旧地图)"));
        tt.put("swamp", new MapData.TerrainType("沼泽", "#556B2F", 2, 0, 1, 2, "海岸沼泽/湿地"));
        tt.put("desert", new MapData.TerrainType("沙漠", "#DDC88D", 1, 2, 1, 2, "沙漠"));
        tt.put("tundra", new MapData.TerrainType("冻土", "#A8C4D8", 1, 1, 1, 2, "冻土"));
        return tt;
    }

    /**
     * Static convenience — generate a full map in one call.
     * @param worldId  world identifier (for terrain types)
     * @param seed     random seed for reproducibility
     * @param mapRadius hex grid radius
     * @param mainRidges number of main ridge chains
     * @param fragments number of fragment ridges
     * @param landRatio target land-to-total ratio
     * @param coastRoughness coastline roughness factor
     * @return generated MapData with hexes
     */
    public static MapData generate(
            String worldId,
            long seed,
            int mapRadius,
            int mainRidges,
            int fragments,
            double landRatio,
            double coastRoughness) {
        var gen = new MapGenerator(seed, mapRadius);
        gen.placeRidges(mainRidges, fragments);
        return gen.generate(landRatio);
    }
}

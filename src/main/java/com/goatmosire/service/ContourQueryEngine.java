package com.goatmosire.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Constructs a query engine from the given continent contour.
     * @param contour the continent contour to query against
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2") // contour is read-only after construction
    public ContourQueryEngine(ContinentContour contour) {
        this.contour = contour;
        this.noise = new SimplexNoise(contour.getSeed());
        this.cache = new LruCache();
    }

    private static class LruCache extends LinkedHashMap<String, TerrainSample> {
        private static final long serialVersionUID = 1L;

        LruCache() {
            super(MAX_CACHE, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TerrainSample> e) {
            return size() > MAX_CACHE;
        }
    }

    // ── Public API ────────────────────────────────────────

    public record TerrainSample(double height, String terrain, String color) {
        public boolean isWater() {
            return "water".equals(terrain);
        }
    }

    /**
     * Query terrain for a hex at axial coordinates (q, r).
     * Results are cached in an LRU cache.
     * @param q axial q coordinate
     * @param r axial r coordinate
     * @return the TerrainSample for the given hex
     */
    public TerrainSample query(int q, int r) {
        String key = q + "_" + r;
        TerrainSample cached = cache.get(key);
        if (cached != null) return cached;

        TerrainSample sample = compute(q, r);
        cache.put(key, sample);
        return sample;
    }

    /**
     * Bulk-query a region and return as MapData (for legacy full-map consumers).
     * @param minQ minimum axial q
     * @param maxQ maximum axial q
     * @param minR minimum axial r
     * @param maxR maximum axial r
     * @return MapData with hexes populated from contour queries
     */
    public com.goatmosire.map.MapData materialize(int minQ, int maxQ, int minR, int maxR) {
        var hexes = new LinkedHashMap<String, com.goatmosire.map.MapData.HexCell>();
        for (int q = minQ; q <= maxQ; q++) {
            for (int r = minR; r <= maxR; r++) {
                int s = -(q + r);
                if (Math.abs(q) + Math.abs(r) + Math.abs(s) > 2 * contour.getRadius()) continue;
                TerrainSample ts = query(q, r);
                hexes.put(
                        q + "_" + r,
                        new com.goatmosire.map.MapData.HexCell(ts.color, ts.terrain, null, null, "", 0, Map.of()));
            }
        }
        return new com.goatmosire.map.MapData(
                30,
                false,
                hexes,
                List.of(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                List.of(),
                List.of(),
                MapGenerator.defaultTerrainTypes(),
                List.of(),
                new LinkedHashMap<>());
    }

    // ═══════════════════════════════════════════════════════
    //  Query computation (mirrors MapGenerator.generate)
    // ═══════════════════════════════════════════════════════

    private TerrainSample compute(int q, int r) {
        // Check editor layers first (highest priority = last in list)
        for (int i = contour.getEditorLayers().size() - 1; i >= 0; i--) {
            ContourLayer layer = contour.getEditorLayers().get(i);
            if (isHexInPolygon(q, r, layer.getBoundary())) {
                String color = terrainColor(layer.getTerrain());
                return new TerrainSample(0.5, layer.getTerrain(), color);
            }
        }

        double px = q + r * 0.5;
        double py = r * 0.8660254;

        // Domain warp
        double wpx = px + noise.noise2(px * 0.018, py * 0.018) * 10;
        double wpy = py + noise.noise2(px * 0.018 + 70, py * 0.018 + 70) * 10;

        // Ridge height
        double ridgeH = computeRidgeHeight(wpx, wpy);

        // Shelf noise
        double shelf = noise.noise2(wpx * contour.getShelfFreq(), wpy * contour.getShelfFreq());
        shelf = Math.max(0, shelf * 0.35 + 0.15);

        // Multi-band noise
        double n1 = noise.noise2(wpx * contour.getLowFreq() + 100, wpy * contour.getLowFreq() + 100);
        double n2 = noise.noise2(wpx * contour.getMidFreq() + 300, wpy * contour.getMidFreq() + 300);
        double n3 = noise.noise2(wpx * contour.getHighFreq() + 500, wpy * contour.getHighFreq() + 500);
        double multi = n1 * 0.40 + n2 * 0.25 + n3 * 0.12;

        // Valley
        double valley = computeValleyPenalty(wpx, wpy);

        // Height assembly — ridge weight raised for wider mountain spines
        double height = ridgeH * 0.68 + shelf * 0.35 + multi * 0.45 - valley;
        height = Math.max(0, height);
        height = Math.pow(height, 0.92); // 0.85→0.92 让山脉更宽更长条

        // Coast noise → sea level
        double coastNoise = noise.noise2(wpx * contour.getCoastFreq() + 77, wpy * contour.getCoastFreq() + 77);
        double seaLevel = contour.getBaseSeaLevel() + coastNoise * 0.35;

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
        for (ContinentContour.Ridge r : contour.getRidges()) {
            double d = distToRidge(px, py, r.getPoints());
            double k = 5.5 + r.getWeight() * 2.0; // 降低衰减 → 山脉更宽更长条
            double h = Math.exp(-d * k / contour.getRadius());
            if (h > best) best = h;
        }
        return best;
    }

    private double computeValleyPenalty(double px, double py) {
        if (contour.getRidges().size() < 2) return 0;
        double d1 = Double.MAX_VALUE;
        double d2 = Double.MAX_VALUE;
        for (ContinentContour.Ridge r : contour.getRidges()) {
            double d = distToRidge(px, py, r.getPoints());
            if (d < d1) {
                d2 = d1;
                d1 = d;
            } else if (d < d2) d2 = d;
        }
        double sigma = contour.getRadius() * 0.10;
        return Math.exp(-d1 * d1 / (2 * sigma * sigma)) * Math.exp(-d2 * d2 / (2 * sigma * sigma)) * 0.30;
    }

    private double distToRidge(double px, double py, List<ContinentContour.Pt> pts) {
        double minD = Double.MAX_VALUE;
        for (int i = 0; i < pts.size() - 1; i++) {
            var a = pts.get(i);
            var b = pts.get(i + 1);
            double dx = b.getX() - a.getX();
            double dy = b.getY() - a.getY();
            double len2 = dx * dx + dy * dy;
            if (len2 < 0.001) {
                double d = Math.sqrt((px - a.getX()) * (px - a.getX()) + (py - a.getY()) * (py - a.getY()));
                if (d < minD) minD = d;
            } else {
                double t = Math.max(0, Math.min(1, ((px - a.getX()) * dx + (py - a.getY()) * dy) / len2));
                double cx = a.getX() + t * dx;
                double cy = a.getY() + t * dy;
                double d = Math.sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy));
                if (d < minD) minD = d;
            }
        }
        return minD;
    }

    private String classify(double height, double px, double py) {
        double moisture = noise.noise2(px * 0.02 + 500, py * 0.02 + 500);

        // mountain → height-driven
        if (height > 0.68) return "mountain";

        // 独立噪声层
        double hillsNoise =
                noise.noise2(px * (2.0 / contour.getRadius()) + 900, py * (2.0 / contour.getRadius()) + 900);
        double plainsNoise =
                noise.noise2(px * (1.5 / contour.getRadius()) + 800, py * (1.5 / contour.getRadius()) + 800);

        // 丘陵包裹山脉：收紧区间减少密度
        if (height > 0.48) return moisture > 0.10 ? "hills" : "plains";
        if (height > 0.36) return moisture > 0.0 ? "hills" : "plains";
        // 低处少量独立丘陵
        if (height > 0.14 && hillsNoise > 0.50) return "hills";

        // 平原：提高阈值 → 更少更清晰的斑块
        if (height > 0.22 && plainsNoise > 0.28) return "plains";
        if (height > 0.14 && plainsNoise > 0.42) return "plains";

        // lowland
        if (height > 0.12) {
            double patch = noise.noise2(px * 0.05 + 600, py * 0.05 + 600);
            if (patch > 0.50) return "plains";
            return "lowland";
        }
        return moisture > 0.15 ? "swamp" : "lowland";
    }

    private static String terrainColor(String t) {
        return switch (t) {
            case "mountain" -> "#6B6B6B";
            case "hills" -> "#A0522D";
            case "lowland" -> "#5B8C3E";
            case "plains" -> "#B8A88A";
            case "swamp" -> "#556B2F";
            case "desert" -> "#DDC88D";
            case "tundra" -> "#A8C4D8";
            case "water" -> "#3295D2";
            default -> "#6CC261";
        };
    }

    /** Ray-casting point-in-polygon test for hex center (q, r) */
    private boolean isHexInPolygon(int q, int r, List<ContinentContour.Pt> poly) {
        if (poly == null || poly.size() < 3) return false;
        double px = q + r * 0.5;
        double py = r * 0.8660254;
        int crossings = 0;
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            var a = poly.get(i);
            var b = poly.get((i + 1) % n);
            if ((a.getY() > py) != (b.getY() > py)) {
                double intersectX = a.getX() + (py - a.getY()) * (b.getX() - a.getX()) / (b.getY() - a.getY());
                if (px < intersectX) crossings++;
            }
        }
        return (crossings & 1) == 1;
    }
}

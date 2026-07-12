package com.goatmosire.service;

import java.util.*;

/**
 * Compact continent contour representation.
 * Replaces full hex grid storage with metadata that can reconstruct any hex on demand.
 *
 * Storage size: ~1-2 KB vs ~1.3 MB full hex grid (1000x reduction for 19K hex map)
 */
public class ContinentContour {
    public long seed;
    public int radius;
    public double landRatio;
    public List<Ridge> ridges;

    // Noise parameters (packed for serialization)
    public double baseSeaLevel;
    public double shelfFreq, lowFreq, midFreq, highFreq, coastFreq;

    // Polygon outlines for quick inside/outside test (optional optimization)
    public List<List<Pt>> landPolygons;

    // Editor-drawn terrain layers (stacked on top of generated contour)
    public List<ContourLayer> editorLayers;

    public ContinentContour() {}

    public ContinentContour(long seed, int radius, double landRatio,
                            List<Ridge> ridges, double baseSeaLevel,
                            double shelfFreq, double lowFreq, double midFreq,
                            double highFreq, double coastFreq) {
        this.seed = seed;
        this.radius = radius;
        this.landRatio = landRatio;
        this.ridges = ridges;
        this.baseSeaLevel = baseSeaLevel;
        this.shelfFreq = shelfFreq;
        this.lowFreq = lowFreq;
        this.midFreq = midFreq;
        this.highFreq = highFreq;
        this.coastFreq = coastFreq;
        this.landPolygons = List.of();
        this.editorLayers = new ArrayList<>();
    }

    // ── Embedded types ────────────────────────────────────

    public static class Ridge {
        public List<Pt> points;
        public double weight;
        public Ridge() {}
        public Ridge(List<Pt> points, double weight) { this.points = points; this.weight = weight; }

        @Override public String toString() { return "Ridge[" + points.size() + "pts, w=" + weight + "]"; }
    }

    public static class Pt {
        public double x, y;
        public Pt() {}
        public Pt(double x, double y) { this.x = x; this.y = y; }

        @Override public String toString() { return String.format("(%.1f,%.1f)", x, y); }
    }
}

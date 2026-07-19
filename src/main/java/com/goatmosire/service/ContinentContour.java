package com.goatmosire.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Compact continent contour representation.
 * Replaces full hex grid storage with metadata that can reconstruct any hex on demand.
 *
 * Storage size: ~1-2 KB vs ~1.3 MB full hex grid (1000x reduction for 19K hex map)
 */
public class ContinentContour {
    private long seed;
    private int radius;
    private double landRatio;
    private List<Ridge> ridges;

    // Noise parameters (packed for serialization)
    private double baseSeaLevel;
    private double shelfFreq;
    private double lowFreq;
    private double midFreq;
    private double highFreq;
    private double coastFreq;

    // Editor-drawn terrain layers (stacked on top of generated contour)
    private List<ContourLayer> editorLayers;

    /** Default constructor for serialization. */
    public ContinentContour() {}

    /**
     * Constructs a continent contour with the given parameters.
     * @param seed random seed for noise generation
     * @param radius hex grid radius
     * @param landRatio target land-to-total ratio
     * @param ridges list of ridge definitions
     * @param baseSeaLevel base sea level threshold
     * @param shelfFreq shelf noise frequency
     * @param lowFreq low-band noise frequency
     * @param midFreq mid-band noise frequency
     * @param highFreq high-band noise frequency
     * @param coastFreq coast noise frequency
     */
    public ContinentContour(
            long seed,
            int radius,
            double landRatio,
            List<Ridge> ridges,
            double baseSeaLevel,
            double shelfFreq,
            double lowFreq,
            double midFreq,
            double highFreq,
            double coastFreq) {
        this.seed = seed;
        this.radius = radius;
        this.landRatio = landRatio;
        this.ridges = ridges == null ? null : new ArrayList<>(ridges);
        this.baseSeaLevel = baseSeaLevel;
        this.shelfFreq = shelfFreq;
        this.lowFreq = lowFreq;
        this.midFreq = midFreq;
        this.highFreq = highFreq;
        this.coastFreq = coastFreq;
        this.editorLayers = new ArrayList<>();
    }

    public long getSeed() {
        return seed;
    }

    public int getRadius() {
        return radius;
    }

    public double getLandRatio() {
        return landRatio;
    }

    public List<Ridge> getRidges() {
        return ridges == null ? null : Collections.unmodifiableList(ridges);
    }

    public double getBaseSeaLevel() {
        return baseSeaLevel;
    }

    public double getShelfFreq() {
        return shelfFreq;
    }

    public double getLowFreq() {
        return lowFreq;
    }

    public double getMidFreq() {
        return midFreq;
    }

    public double getHighFreq() {
        return highFreq;
    }

    public double getCoastFreq() {
        return coastFreq;
    }

    public List<ContourLayer> getEditorLayers() {
        return editorLayers == null ? null : Collections.unmodifiableList(editorLayers);
    }

    public void setEditorLayers(List<ContourLayer> editorLayers) {
        this.editorLayers = editorLayers == null ? null : new ArrayList<>(editorLayers);
    }

    // ── Embedded types ────────────────────────────────────

    public static class Ridge {
        private List<Pt> points;
        private double weight;

        /** Default constructor. */
        public Ridge() {}

        /**
         * Constructs a contour ridge with the given points and weight.
         * @param points ridge control points
         * @param weight ridge height weight
         */
        public Ridge(List<Pt> points, double weight) {
            this.points = points == null ? null : new ArrayList<>(points);
            this.weight = weight;
        }

        public List<Pt> getPoints() {
            return points == null ? null : Collections.unmodifiableList(points);
        }

        public double getWeight() {
            return weight;
        }

        @Override
        public String toString() {
            return "Ridge[" + (points != null ? points.size() : 0) + "pts, w=" + weight + "]";
        }
    }

    public static class Pt {
        private double x;
        private double y;

        /** Default constructor. */
        public Pt() {}

        /**
         * Constructs a point with the given coordinates.
         * @param x x coordinate
         * @param y y coordinate
         */
        public Pt(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        @Override
        public String toString() {
            return String.format("(%.1f,%.1f)", x, y);
        }
    }
}

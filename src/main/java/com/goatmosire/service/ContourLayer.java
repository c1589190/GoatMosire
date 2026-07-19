package com.goatmosire.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An editor-drawn contour layer for terrain modification.
 * Each layer is a closed polygon with an assigned terrain type.
 * Layers stack: base contour first, editor layers on top (later = higher priority).
 */
public class ContourLayer {
    /** Terrain type to assign (e.g. "mountain", "forest", "plains", "water") */
    private String terrain;

    /** Polygon boundary points (closed — first == last implicit) */
    private List<ContinentContour.Pt> boundary;

    /** Interior seed hex key for flood fill reconstruction */
    private String seedKey;

    /** Default constructor for serialization. */
    public ContourLayer() {}

    /**
     * Returns the terrain type.
     * @return the terrain type
     */
    public String getTerrain() {
        return terrain;
    }

    /**
     * Returns the polygon boundary points.
     * @return the polygon boundary points
     */
    public List<ContinentContour.Pt> getBoundary() {
        return boundary == null ? null : Collections.unmodifiableList(boundary);
    }

    /**
     * Returns the interior seed hex key for flood fill reconstruction.
     * @return the interior seed hex key
     */
    public String getSeedKey() {
        return seedKey;
    }

    /**
     * Constructs a contour layer with the given terrain, boundary, and seed.
     * @param terrain terrain type (e.g. "mountain", "forest", "water")
     * @param boundary polygon boundary points
     * @param seedKey interior seed hex key for flood fill
     */
    public ContourLayer(String terrain, List<ContinentContour.Pt> boundary, String seedKey) {
        this.terrain = terrain;
        this.boundary = boundary == null ? null : new ArrayList<>(boundary);
        this.seedKey = seedKey;
    }

    /**
     * Single-hex edit: create a small polygon covering one hex.
     * @param q axial q coordinate
     * @param r axial r coordinate
     * @param terrain terrain type to assign
     * @return a ContourLayer for the given hex
     */
    public static ContourLayer singleHex(int q, int r, String terrain) {
        var pts = List.of(
                new ContinentContour.Pt(q + 0.5, r - 0.3),
                new ContinentContour.Pt(q + 0.5, r + 0.3),
                new ContinentContour.Pt(q - 0.5, r + 0.3),
                new ContinentContour.Pt(q - 0.5, r - 0.3));
        return new ContourLayer(terrain, pts, q + "_" + r);
    }

    @Override
    public String toString() {
        return "ContourLayer[" + terrain + ", " + boundary.size() + "pts, seed=" + seedKey + "]";
    }
}

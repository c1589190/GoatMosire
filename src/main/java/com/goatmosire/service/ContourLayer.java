package com.goatmosire.service;

import java.util.*;

/**
 * An editor-drawn contour layer for terrain modification.
 * Each layer is a closed polygon with an assigned terrain type.
 * Layers stack: base contour first, editor layers on top (later = higher priority).
 */
public class ContourLayer {
    /** Terrain type to assign (e.g. "mountain", "forest", "plains", "water") */
    public String terrain;

    /** Polygon boundary points (closed — first == last implicit) */
    public List<ContinentContour.Pt> boundary;

    /** Interior seed hex key for flood fill reconstruction */
    public String seedKey;

    public ContourLayer() {}

    public ContourLayer(String terrain, List<ContinentContour.Pt> boundary, String seedKey) {
        this.terrain = terrain;
        this.boundary = boundary;
        this.seedKey = seedKey;
    }

    /** Single-hex edit: 1-point polygon with explicit hex set */
    public static ContourLayer singleHex(int q, int r, String terrain) {
        var pts = List.of(
            new ContinentContour.Pt(q + 0.5, r - 0.3),
            new ContinentContour.Pt(q + 0.5, r + 0.3),
            new ContinentContour.Pt(q - 0.5, r + 0.3),
            new ContinentContour.Pt(q - 0.5, r - 0.3)
        );
        return new ContourLayer(terrain, pts, q + "_" + r);
    }

    @Override public String toString() {
        return "ContourLayer[" + terrain + ", " + boundary.size() + "pts, seed=" + seedKey + "]";
    }
}

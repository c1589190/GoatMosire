package com.goatmosire.service;

import com.goatmosire.map.MapData;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hex grid geometry utilities shared by TerrainCanvas and the editor.
 *
 * Coordinate conventions match the frontend:
 *   hexToPixel: {@code x = SIZE * (√3*q + √3/2*r), y = SIZE * (3/2*r)}
 *   pixelToHex: axial cube rounding
 */
public final class TerrainGeometry {

    private TerrainGeometry() {}

    /** Hex size in pixels — must match the frontend GRID constant. */
    public static final double SIZE = 30.0;

    /** The 6 axial direction vectors. */
    // Hex neighbor directions indexed by edge. Order must match corner angles (60*i - 30°):
    // Edge 0 (right) → E(1,0), Edge 1 (bottom-right) → SE(0,1),
    // Edge 2 (bottom-left) → SW(-1,1), Edge 3 (left) → W(-1,0),
    // Edge 4 (top-left) → NW(0,-1), Edge 5 (top-right) → NE(1,-1)
    static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 1}, {-1, 0}, {0, -1}, {1, -1}};

    /**
     * Hex center to pixel coordinates.
     *
     * @param q axial column
     * @param r axial row
     * @return {x, y} pixel coordinates
     */
    public static double[] hexToPixel(int q, int r) {
        double x = SIZE * (Math.sqrt(3) * q + Math.sqrt(3) / 2.0 * r);
        double y = SIZE * (3.0 / 2.0 * r);
        return new double[] {x, y};
    }

    /**
     * Pixel to fractional hex coordinates (not rounded).
     *
     * @param px pixel x
     * @param py pixel y
     * @return {fq, fr} fractional axial coordinates
     */
    public static double[] pixelToHexFrac(double px, double py) {
        double fq = (Math.sqrt(3) / 3.0 * px - 1.0 / 3.0 * py) / SIZE;
        double fr = (2.0 / 3.0 * py) / SIZE;
        return new double[] {fq, fr};
    }

    /**
     * Pixel to the nearest hex coordinates (cube-rounded).
     *
     * @param px pixel x
     * @param py pixel y
     * @return {q, r} nearest axial hex coordinates
     */
    public static int[] pixelToHex(double px, double py) {
        double[] f = pixelToHexFrac(px, py);
        return hexRound(f[0], f[1]);
    }

    /**
     * Axial cube rounding.
     *
     * @param fq fractional q
     * @param fr fractional r
     * @return {q, r} rounded axial coordinates
     */
    public static int[] hexRound(double fq, double fr) {
        double fs = -fq - fr;
        int q = (int) Math.round(fq);
        int r = (int) Math.round(fr);
        int s = (int) Math.round(fs);
        double dq = Math.abs(q - fq);
        double dr = Math.abs(r - fr);
        double ds = Math.abs(s - fs);
        if (dq > dr && dq > ds) q = -r - s;
        else if (dr > ds) r = -q - s;
        return new int[] {q, r};
    }

    /**
     * Hex key format: "q_r".
     *
     * @param q axial column
     * @param r axial row
     * @return key string "q_r"
     */
    public static String hexKey(int q, int r) {
        return q + "_" + r;
    }

    /**
     * Parse hex key back to coordinates.
     *
     * @param key hex key "q_r"
     * @return {q, r} axial coordinates
     */
    public static int[] parseHexKey(String key) {
        String[] parts = key.split("_");
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    // ── Polygon tests ────────────────────────────────────────

    /**
     * Ray-casting point-in-polygon test.
     * Edge case: points exactly on boundary return false (conservative).
     *
     * @param px   point x
     * @param py   point y
     * @param poly polygon vertex list
     * @return true if point is strictly inside the polygon
     */
    public static boolean pointInPolygon(double px, double py, List<MapData.Pt> poly) {
        if (poly == null || poly.size() < 3) return false;
        boolean inside = false;
        int n = poly.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            MapData.Pt a = poly.get(j);
            MapData.Pt b = poly.get(i);
            if ((a.y() > py) != (b.y() > py)) {
                double intersectX = a.x() + (py - a.y()) * (b.x() - a.x()) / (b.y() - a.y());
                if (px < intersectX) inside = !inside;
            }
        }
        return inside;
    }

    // ── Hex set ↔ polygon boundary ───────────────────────────

    /**
     * Compute the set of hex keys whose centers lie inside the polygon boundary.
     * Uses flood-fill from a seed hex. Falls back to centroid search if the
     * supplied seed is outside the polygon.
     *
     * @param boundary  closed polygon (first != last OK, either way accepted)
     * @param mapRadius safety bound for flood fill (hex coord limit)
     * @param seedKey   preferred seed hex or empty string
     * @return set of hex keys inside the polygon boundary
     */
    public static Set<String> hexSetFromPolygon(List<MapData.Pt> boundary, int mapRadius, String seedKey) {
        if (boundary == null || boundary.size() < 3) return Collections.emptySet();

        // Normalize: ensure closed
        List<MapData.Pt> poly = new ArrayList<>(boundary);
        MapData.Pt first = poly.get(0);
        MapData.Pt last = poly.get(poly.size() - 1);
        if (Math.abs(first.x() - last.x()) > 0.01 || Math.abs(first.y() - last.y()) > 0.01) poly.add(first);

        // Build candidate seed keys
        List<String> seeds = new ArrayList<>();
        if (seedKey != null && !seedKey.isEmpty()) seeds.add(seedKey);

        // Centroid as fallback seed
        double cx = 0;
        double cy = 0;
        for (MapData.Pt p : poly) {
            cx += p.x();
            cy += p.y();
        }
        cx /= poly.size();
        cy /= poly.size();
        int[] ch = pixelToHex(cx, cy);
        seeds.add(hexKey(ch[0], ch[1]));

        // First boundary point as another fallback
        int[] fh = pixelToHex(poly.get(0).x(), poly.get(0).y());
        seeds.add(hexKey(fh[0], fh[1]));

        int safety = Math.max(mapRadius * 3, 200);
        Set<String> hexSet = new HashSet<>();
        Set<String> visited = new HashSet<>();

        for (String sk : seeds) {
            int[] coords = parseHexKey(sk);
            double[] sp = hexToPixel(coords[0], coords[1]);
            if (!pointInPolygon(sp[0], sp[1], poly)) continue;

            // Found valid seed → flood fill
            Deque<String> stack = new ArrayDeque<>();
            stack.push(sk);
            while (!stack.isEmpty()) {
                String key = stack.pop();
                if (!visited.add(key)) continue;
                int[] h = parseHexKey(key);
                double[] hpxy = hexToPixel(h[0], h[1]);
                if (!pointInPolygon(hpxy[0], hpxy[1], poly)) continue;
                hexSet.add(key);
                for (int[] d : DIRS) {
                    int nq = h[0] + d[0];
                    int nr = h[1] + d[1];
                    if (Math.abs(nq) > safety || Math.abs(nr) > safety) continue;
                    String nk = hexKey(nq, nr);
                    if (!visited.contains(nk)) stack.push(nk);
                }
            }
            break; // found working seed
        }

        // Also include boundary hexes (their centers are on polygon edge, may be missed)
        for (MapData.Pt pt : poly) {
            int[] h = pixelToHex(pt.x(), pt.y());
            hexSet.add(hexKey(h[0], h[1]));
            // Also add one neighbor inward for each boundary point (fuzz overlap)
            for (int[] d : DIRS) {
                int nq = h[0] + d[0];
                int nr = h[1] + d[1];
                String nk = hexKey(nq, nr);
                if (hexSet.contains(nk)) break; // already in set, good
            }
        }
        return hexSet;
    }

    /**
     * Reconstruct boundary polygon(s) from a hex set by tracing exposed edges.
     * Works for concave shapes (unlike angular sort around centroid).
     *
     * <p>Returns ALL boundary loops: the outermost ring first, then hole rings.
     * For a CR that wraps around other-terrain regions, the outer boundary alone
     * would fill those regions — holes let the renderer carve them out via evenodd.
     *
     * @param hexSet set of hex keys
     * @return outer boundary polygon (largest bbox area)
     * @deprecated use {@link #hexSetToBoundaryWithHoles(Set)} for hole-aware rendering
     */
    @Deprecated
    public static List<MapData.Pt> hexSetToBoundary(Set<String> hexSet) {
        List<List<MapData.Pt>> all = hexSetToBoundaryWithHoles(hexSet);
        if (all.isEmpty()) return List.of();
        // For backward compat: return only the outer ring (largest BBox area)
        List<MapData.Pt> best = all.get(0);
        double bestArea = bboxArea(best);
        for (int i = 1; i < all.size(); i++) {
            double a = bboxArea(all.get(i));
            if (a > bestArea) {
                best = all.get(i);
                bestArea = a;
            }
        }
        return best;
    }

    /**
     * Reconstruct ALL boundary loops from a hex set — outer ring + hole rings.
     * The first returned ring is the outer boundary (largest BBox area);
     * subsequent rings are hole boundaries.  Use with Canvas evenodd fill
     * to render polygons with holes correctly.
     *
     * <p>Each ring is a closed polygon (first point == last point).
     *
     * @param hexSet set of hex keys
     * @return list of boundary loops; first element is the outer ring, rest are holes
     */
    public static List<List<MapData.Pt>> hexSetToBoundaryWithHoles(Set<String> hexSet) {
        if (hexSet == null || hexSet.size() < 3) return List.of();
        Set<String> set = new HashSet<>(hexSet);

        // Collect exposed edge segments.
        Map<String, double[]> edgeSegments = new LinkedHashMap<>();

        for (String key : set) {
            int[] h = parseHexKey(key);
            double[] pix = hexToPixel(h[0], h[1]);
            double cx = pix[0];
            double cy = pix[1];

            double[][] corners = new double[6][2];
            for (int i = 0; i < 6; i++) {
                double angle = Math.toRadians(60 * i - 30);
                corners[i][0] = cx + SIZE * Math.cos(angle);
                corners[i][1] = cy + SIZE * Math.sin(angle);
            }

            for (int d = 0; d < 6; d++) {
                int nq = h[0] + DIRS[d][0];
                int nr = h[1] + DIRS[d][1];
                if (set.contains(hexKey(nq, nr))) continue; // shared edge

                int c1 = d;
                int c2 = (d + 1) % 6;
                double x1 = corners[c1][0];
                double y1 = corners[c1][1];
                double x2 = corners[c2][0];
                double y2 = corners[c2][1];

                String k1 = cornerKey(x1, y1);
                String k2 = cornerKey(x2, y2);
                String segKey = k1.compareTo(k2) < 0 ? k1 + "-" + k2 : k2 + "-" + k1;
                edgeSegments.putIfAbsent(segKey, new double[] {x1, y1, x2, y2});
            }
        }

        if (edgeSegments.size() < 3) return List.of();

        // Build adjacency graph
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (double[] seg : edgeSegments.values()) {
            String a = cornerKey(seg[0], seg[1]);
            String b = cornerKey(seg[2], seg[3]);
            graph.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
            graph.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
        }

        // Walk ALL disconnected boundary loops
        Set<String> globalVisited = new HashSet<>();
        List<List<MapData.Pt>> allLoops = new ArrayList<>();

        for (String start : graph.keySet()) {
            if (globalVisited.contains(start)) continue;

            List<MapData.Pt> loop = new ArrayList<>();
            String cur = start;
            while (cur != null) {
                double[] pt = parsePt(cur);
                loop.add(new MapData.Pt(pt[0], pt[1]));
                globalVisited.add(cur);

                String next = null;
                for (String nb : graph.getOrDefault(cur, List.of())) {
                    if (!globalVisited.contains(nb)) {
                        next = nb;
                        break;
                    }
                }
                if (next == null) {
                    if (!loop.isEmpty()) {
                        loop.add(new MapData.Pt(loop.get(0).x(), loop.get(0).y()));
                    }
                    break;
                }
                cur = next;
            }
            if (loop.size() >= 4) allLoops.add(loop);
        }

        if (allLoops.isEmpty()) return List.of();

        // Sort: outer ring (largest BBox area) first, then holes
        allLoops.sort((a, b) -> Double.compare(bboxArea(b), bboxArea(a)));
        return allLoops;
    }

    // Integer-micron keys for robust corner matching (avoid floating-point drift)
    private static String cornerKey(double x, double y) {
        return Math.round(x * 1000) + "_" + Math.round(y * 1000);
    }

    private static double bboxArea(List<MapData.Pt> pts) {
        if (pts == null || pts.isEmpty()) return 0;
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (var p : pts) {
            if (p.x() < minX) minX = p.x();
            if (p.x() > maxX) maxX = p.x();
            if (p.y() < minY) minY = p.y();
            if (p.y() > maxY) maxY = p.y();
        }
        return (maxX - minX) * (maxY - minY);
    }

    private static double[] parsePt(String s) {
        String[] parts = s.split("_");
        return new double[] {Long.parseLong(parts[0]) / 1000.0, Long.parseLong(parts[1]) / 1000.0};
    }

    /** Ramer–Douglas–Peucker simplification. Removes points that lie near the
     *  line between their neighbors, keeping only the shape-defining corners. */
    static List<MapData.Pt> simplifyBoundary(List<MapData.Pt> pts, double epsilon) {
        if (pts.size() < 4) return new ArrayList<>(pts);
        return rdp(pts, 0, pts.size() - 1, epsilon);
    }

    private static List<MapData.Pt> rdp(List<MapData.Pt> pts, int start, int end, double eps) {
        double maxDist = 0;
        int maxIdx = start;
        MapData.Pt a = pts.get(start);
        MapData.Pt b = pts.get(end);
        for (int i = start + 1; i < end; i++) {
            double d = perpendicularDist(pts.get(i), a, b);
            if (d > maxDist) {
                maxDist = d;
                maxIdx = i;
            }
        }
        List<MapData.Pt> result = new ArrayList<>();
        if (maxDist > eps) {
            List<MapData.Pt> left = rdp(pts, start, maxIdx, eps);
            List<MapData.Pt> right = rdp(pts, maxIdx, end, eps);
            result.addAll(left);
            if (!result.isEmpty()) result.remove(result.size() - 1);
            result.addAll(right);
        } else {
            result.add(a);
            result.add(b);
        }
        return result;
    }

    private static double perpendicularDist(MapData.Pt p, MapData.Pt a, MapData.Pt b) {
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        if (Math.abs(dx) < 0.001 && Math.abs(dy) < 0.001) return Math.hypot(p.x() - a.x(), p.y() - a.y());
        double t = ((p.x() - a.x()) * dx + (p.y() - a.y()) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));
        double projX = a.x() + t * dx;
        double projY = a.y() + t * dy;
        return Math.hypot(p.x() - projX, p.y() - projY);
    }

    // ── Hex set relationships ────────────────────────────────

    /**
     * True if the two hex sets share at least one hex.
     *
     * @param a first hex set
     * @param b second hex set
     * @return true if the sets intersect
     */
    public static boolean intersects(Set<String> a, Set<String> b) {
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        for (String k : smaller) {
            if (larger.contains(k)) return true;
        }
        return false;
    }

    /**
     * True if any hex in {@code a} is neighbor to a hex in {@code b}.
     *
     * @param a first hex set
     * @param b second hex set
     * @return true if any hex in a is adjacent to a hex in b
     */
    public static boolean isAdjacent(Set<String> a, Set<String> b) {
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        for (String k : smaller) {
            int[] h = parseHexKey(k);
            for (int[] d : DIRS) {
                if (larger.contains(hexKey(h[0] + d[0], h[1] + d[1]))) return true;
            }
        }
        return false;
    }

    /**
     * True if sets overlap or any hex from one is adjacent to the other.
     *
     * @param a first hex set
     * @param b second hex set
     * @return true if the sets intersect or are adjacent
     */
    public static boolean overlapsOrAdjacent(Set<String> a, Set<String> b) {
        return intersects(a, b) || isAdjacent(a, b);
    }
}

package com.goatmosire.service;

import com.gsim.map.MapData;
import java.util.*;

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
    public static final int[][] DIRS = {{1,0},{1,-1},{0,-1},{-1,0},{-1,1},{0,1}};

    /** Hex center to pixel coordinates. */
    public static double[] hexToPixel(int q, int r) {
        double x = SIZE * (Math.sqrt(3) * q + Math.sqrt(3) / 2.0 * r);
        double y = SIZE * (3.0 / 2.0 * r);
        return new double[]{x, y};
    }

    /** Pixel to fractional hex coordinates (not rounded). */
    public static double[] pixelToHexFrac(double px, double py) {
        double fq = (Math.sqrt(3) / 3.0 * px - 1.0 / 3.0 * py) / SIZE;
        double fr = (2.0 / 3.0 * py) / SIZE;
        return new double[]{fq, fr};
    }

    /** Pixel to the nearest hex coordinates (cube-rounded). */
    public static int[] pixelToHex(double px, double py) {
        double[] f = pixelToHexFrac(px, py);
        return hexRound(f[0], f[1]);
    }

    /** Axial cube rounding. */
    public static int[] hexRound(double fq, double fr) {
        double fs = -fq - fr;
        int q = (int) Math.round(fq), r = (int) Math.round(fr), s = (int) Math.round(fs);
        double dq = Math.abs(q - fq), dr = Math.abs(r - fr), ds = Math.abs(s - fs);
        if (dq > dr && dq > ds) q = -r - s;
        else if (dr > ds) r = -q - s;
        return new int[]{q, r};
    }

    /** Hex key format: "q_r" */
    public static String hexKey(int q, int r) { return q + "_" + r; }

    /** Parse hex key back to coordinates. */
    public static int[] parseHexKey(String key) {
        String[] parts = key.split("_");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    // ── Polygon tests ────────────────────────────────────────

    /**
     * Ray-casting point-in-polygon test.
     * Edge case: points exactly on boundary return false (conservative).
     */
    public static boolean pointInPolygon(double px, double py, List<MapData.Pt> poly) {
        if (poly == null || poly.size() < 3) return false;
        boolean inside = false;
        int n = poly.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            MapData.Pt a = poly.get(j), b = poly.get(i);
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
     */
    public static Set<String> hexSetFromPolygon(List<MapData.Pt> boundary, int mapRadius, String seedKey) {
        if (boundary == null || boundary.size() < 3) return Collections.emptySet();

        // Normalize: ensure closed
        List<MapData.Pt> poly = new ArrayList<>(boundary);
        MapData.Pt first = poly.get(0), last = poly.get(poly.size() - 1);
        if (Math.abs(first.x() - last.x()) > 0.01 || Math.abs(first.y() - last.y()) > 0.01)
            poly.add(first);

        // Build candidate seed keys
        List<String> seeds = new ArrayList<>();
        if (seedKey != null && !seedKey.isEmpty()) seeds.add(seedKey);

        // Centroid as fallback seed
        double cx = 0, cy = 0;
        for (MapData.Pt p : poly) { cx += p.x(); cy += p.y(); }
        cx /= poly.size(); cy /= poly.size();
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
                    int nq = h[0] + d[0], nr = h[1] + d[1];
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
                int nq = h[0] + d[0], nr = h[1] + d[1];
                String nk = hexKey(nq, nr);
                if (hexSet.contains(nk)) break; // already in set, good
            }
        }
        return hexSet;
    }

    /**
     * Reconstruct a polygon boundary from a hex set by extracting edge hex
     * centers, sorting angularly, then simplifying with RDP.
     */
    public static List<MapData.Pt> hexSetToBoundary(Set<String> hexSet) {
        if (hexSet == null || hexSet.size() < 3) return List.of();
        Set<String> set = new HashSet<>(hexSet);

        // Collect edge hex centers
        List<double[]> edgeCenters = new ArrayList<>();
        for (String key : set) {
            int[] h = parseHexKey(key);
            boolean isEdge = false;
            for (int[] d : DIRS) {
                if (!set.contains(hexKey(h[0] + d[0], h[1] + d[1]))) { isEdge = true; break; }
            }
            if (isEdge) edgeCenters.add(hexToPixel(h[0], h[1]));
        }

        if (edgeCenters.size() < 3) return List.of();

        // Angular sort around centroid
        double sx = 0, sy = 0;
        for (double[] e : edgeCenters) { sx += e[0]; sy += e[1]; }
        final double cx = sx / edgeCenters.size();
        final double cy = sy / edgeCenters.size();

        edgeCenters.sort(Comparator.comparingDouble(
            e -> Math.atan2(e[1] - cy, e[0] - cx)));

        List<MapData.Pt> raw = new ArrayList<>();
        for (double[] e : edgeCenters) raw.add(new MapData.Pt(e[0], e[1]));
        raw.add(new MapData.Pt(edgeCenters.get(0)[0], edgeCenters.get(0)[1])); // close

        // Simplify with RDP — epsilon = 2 hex pixels removes interior detail
        return simplifyBoundary(raw, SIZE * 2.0);
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
        MapData.Pt a = pts.get(start), b = pts.get(end);
        for (int i = start + 1; i < end; i++) {
            double d = perpendicularDist(pts.get(i), a, b);
            if (d > maxDist) { maxDist = d; maxIdx = i; }
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
        double dx = b.x() - a.x(), dy = b.y() - a.y();
        if (Math.abs(dx) < 0.001 && Math.abs(dy) < 0.001)
            return Math.hypot(p.x() - a.x(), p.y() - a.y());
        double t = ((p.x() - a.x()) * dx + (p.y() - a.y()) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));
        double projX = a.x() + t * dx, projY = a.y() + t * dy;
        return Math.hypot(p.x() - projX, p.y() - projY);
    }

    // ── Hex set relationships ────────────────────────────────

    /** True if the two hex sets share at least one hex. */
    public static boolean intersects(Set<String> a, Set<String> b) {
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        for (String k : smaller) if (larger.contains(k)) return true;
        return false;
    }

    /** True if any hex in {@code a} is neighbor to a hex in {@code b}. */
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

    /** True if sets overlap or any hex from one is adjacent to the other. */
    public static boolean overlapsOrAdjacent(Set<String> a, Set<String> b) {
        return intersects(a, b) || isAdjacent(a, b);
    }
}

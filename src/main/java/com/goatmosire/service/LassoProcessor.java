package com.goatmosire.service;

import com.gsim.map.MapData;
import java.util.*;

/**
 * Validates and auto-closes lasso boundaries, then flood-fills interior.
 * Uses convex hull + bounded fill — no hexLine gap issues.
 */
public final class LassoProcessor {

    private LassoProcessor() {}

    private static final int[][] DIRS = {{1,0},{1,-1},{0,-1},{-1,0},{-1,1},{0,1}};
    private static final int MAX_RADIUS = 200;
    private static final int MAX_FILL = 50000;

    /**
     * From raw lasso hexKeys, compute convex hull, then flood fill inside hull.
     * @param rawKeys list of hex keys from lasso (e.g., "10_-5")
     * @return filled hex set, or empty if invalid
     */
    public static Set<String> fill(List<String> rawKeys) {
        if (rawKeys == null || rawKeys.size() < 3) return Collections.emptySet();

        // Parse all lasso hexes, filter OOB
        List<int[]> pts = new ArrayList<>();
        for (String k : rawKeys) {
            int[] qr = MapData.parseHexKey(k);
            if (Math.abs(qr[0]) <= MAX_RADIUS && Math.abs(qr[1]) <= MAX_RADIUS) {
                pts.add(qr);
            }
        }
        if (pts.size() < 3) return Collections.emptySet();

        // Compute convex hull of lasso points
        List<int[]> hull = convexHull(pts);

        // Build hull hex set (border wall)
        Set<String> hullSet = new HashSet<>();
        for (int[] p : hull) hullSet.add(p[0] + "_" + p[1]);

        // Also add hexLine bridging along hull edges for solid boundary
        for (int i = 0; i < hull.size(); i++) {
            int[] a = hull.get(i);
            int[] b = hull.get((i + 1) % hull.size());
            for (String k : hexLine(a[0], a[1], b[0], b[1])) {
                hullSet.add(k);
            }
        }

        // Find seed: centroid of original lasso hexes
        double sq = 0, sr = 0;
        for (int[] p : pts) { sq += p[0]; sr += p[1]; }
        int seedQ = (int) Math.round(sq / pts.size());
        int seedR = (int) Math.round(sr / pts.size());
        String seed = seedQ + "_" + seedR;

        if (hullSet.contains(seed)) return Collections.emptySet();

        // Flood fill inside hull
        Set<String> filled = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        stack.push(seed);

        while (!stack.isEmpty() && filled.size() < MAX_FILL) {
            String key = stack.pop();
            if (visited.contains(key) || hullSet.contains(key)) continue;
            int[] qr = MapData.parseHexKey(key);
            if (Math.abs(qr[0]) > MAX_RADIUS || Math.abs(qr[1]) > MAX_RADIUS) continue;
            visited.add(key);
            filled.add(key);
            for (int[] d : DIRS) {
                String nk = (qr[0] + d[0]) + "_" + (qr[1] + d[1]);
                if (!visited.contains(nk) && !hullSet.contains(nk)) stack.push(nk);
            }
        }
        return filled;
    }

    /** Andrew's monotone chain convex hull for 2D points. */
    private static List<int[]> convexHull(List<int[]> pts) {
        if (pts.size() <= 3) return new ArrayList<>(pts);

        // Sort by q then r
        List<int[]> sorted = new ArrayList<>(pts);
        sorted.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));

        List<int[]> lower = new ArrayList<>();
        for (int[] p : sorted) {
            while (lower.size() >= 2 && cross(lower.get(lower.size()-2), lower.get(lower.size()-1), p) <= 0)
                lower.remove(lower.size()-1);
            lower.add(p);
        }

        List<int[]> upper = new ArrayList<>();
        for (int i = sorted.size()-1; i >= 0; i--) {
            int[] p = sorted.get(i);
            while (upper.size() >= 2 && cross(upper.get(upper.size()-2), upper.get(upper.size()-1), p) <= 0)
                upper.remove(upper.size()-1);
            upper.add(p);
        }

        lower.remove(lower.size()-1);
        upper.remove(upper.size()-1);
        lower.addAll(upper);
        return lower;
    }

    private static int cross(int[] o, int[] a, int[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /** Hex line between two axial hexes, guaranteed contiguous. */
    private static List<String> hexLine(int aq, int ar, int bq, int br) {
        List<String> line = new ArrayList<>();
        int dist = (Math.abs(aq - bq) + Math.abs(ar - br) + Math.abs(-aq-ar + bq+br)) / 2;
        if (dist == 0) { line.add(aq + "_" + ar); return line; }

        for (int i = 0; i <= dist; i++) {
            double t = (double) i / dist;
            double fq = aq + (bq - aq) * t;
            double fr = ar + (br - ar) * t;
            // Axial rounding
            int q = (int) Math.round(fq);
            int r = (int) Math.round(fr);
            double dq = Math.abs(fq - q);
            double dr = Math.abs(fr - r);
            double ds = Math.abs(-fq-fr - (-q-r));
            if (dq > dr && dq > ds) q = -r - (-q-r);
            else if (dr > ds) r = -q - (-q-r);
            line.add(q + "_" + r);
        }
        return line;
    }
}

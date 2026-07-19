package com.goatmosire.service;

import com.goatmosire.map.MapData;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Closes lasso by bridging consecutive hexes (in draw order) with
 * Bresenham hex line, then flood fills interior.
 */
public final class LassoProcessor {

    private LassoProcessor() {
        // utility class
    }

    private static final int[][] DIRS = {{1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}};
    private static final int MAX_RADIUS = 200;
    private static final int MAX_FILL = 30000;

    /**
     * Compute the flood-filled hex set from a lasso's perimeter hex keys.
     * Bridges consecutive hexes with Bresenham lines, then flood-fills interior.
     * @param rawKeys lasso perimeter hex keys in draw order
     * @return the set of hex keys inside the closed lasso, or empty if invalid
     */
    public static Set<String> fill(List<String> rawKeys) {
        if (rawKeys == null || rawKeys.size() < 3) return Collections.emptySet();

        // Parse and filter OOB
        List<int[]> pts = new ArrayList<>();
        for (String k : rawKeys) {
            int[] qr = MapData.parseHexKey(k);
            if (Math.abs(qr[0]) <= MAX_RADIUS && Math.abs(qr[1]) <= MAX_RADIUS) pts.add(qr);
        }
        if (pts.size() < 3) return Collections.emptySet();

        // Build wall: lasso hexes + Bresenham bridges between consecutive points
        Set<String> wall = new LinkedHashSet<>();
        List<int[]> deduped = deduplicate(pts);
        for (int[] p : deduped) {
            wall.add(p[0] + "_" + p[1]);
        }
        // Bridge consecutive hexes (including last→first)
        for (int i = 0; i < deduped.size(); i++) {
            int[] a = deduped.get(i);
            int[] b = deduped.get((i + 1) % deduped.size());
            if (hexDist(a[0], a[1], b[0], b[1]) <= 1) continue;
            for (String k : hexLineBresenham(a[0], a[1], b[0], b[1])) {
                wall.add(k);
            }
        }

        // Find seed: centroid of wall
        double sq = 0;
        double sr = 0;
        for (int[] p : deduped) {
            sq += p[0];
            sr += p[1];
        }
        int seedQ = (int) Math.round(sq / deduped.size());
        int seedR = (int) Math.round(sr / deduped.size());
        String seed = seedQ + "_" + seedR;
        if (wall.contains(seed)) {
            // Seed is on wall — nudge inward
            seed = findInsideSeed(wall, seedQ, seedR);
            if (seed == null) return Collections.emptySet();
        }

        // Flood fill inside wall
        Set<String> filled = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(seed);

        while (!stack.isEmpty() && filled.size() < MAX_FILL) {
            String key = stack.pop();
            if (filled.contains(key) || wall.contains(key)) continue;
            int[] qr = MapData.parseHexKey(key);
            if (Math.abs(qr[0]) > MAX_RADIUS || Math.abs(qr[1]) > MAX_RADIUS) continue;
            filled.add(key);
            for (int[] d : DIRS) {
                String nk = (qr[0] + d[0]) + "_" + (qr[1] + d[1]);
                if (!filled.contains(nk) && !wall.contains(nk)) stack.push(nk);
            }
        }

        // If fill is suspiciously large (leaked), return empty
        if (filled.size() >= MAX_FILL) return Collections.emptySet();

        return filled;
    }

    /** Remove consecutive duplicate hexes. */
    private static List<int[]> deduplicate(List<int[]> pts) {
        List<int[]> out = new ArrayList<>();
        int[] prev = null;
        for (int[] p : pts) {
            if (prev == null || prev[0] != p[0] || prev[1] != p[1]) {
                out.add(p);
                prev = p;
            }
        }
        return out;
    }

    /** Find a hex inside the wall near (q,r) by trying neighbors. */
    private static String findInsideSeed(Set<String> wall, int q, int r) {
        for (int[] d : DIRS) {
            for (int[] d2 : DIRS) {
                String k = (q + d[0] + d2[0]) + "_" + (r + d[1] + d2[1]);
                if (!wall.contains(k)) return k;
            }
        }
        return null;
    }

    // ── Bresenham hex line (guaranteed contiguous, no gaps) ─

    private static List<String> hexLineBresenham(int aq, int ar, int bq, int br) {
        List<String> line = new ArrayList<>();
        int dist = hexDist(aq, ar, bq, br);
        if (dist == 0) {
            line.add(aq + "_" + ar);
            return line;
        }

        // Convert to cube coords
        int ax = aq;
        int ay = ar;
        int az = -aq - ar;
        int bx = bq;
        int by = br;
        int bz = -bq - br;

        for (int i = 0; i <= dist; i++) {
            double t = (double) i / dist;
            double fx = ax + (bx - ax) * t;
            double fy = ay + (by - ay) * t;
            double fz = az + (bz - az) * t;
            int[] qr = cubeRound(fx, fy, fz);
            line.add(qr[0] + "_" + qr[1]);
        }
        return line;
    }

    private static int[] cubeRound(double fx, double fy, double fz) {
        int rx = (int) Math.round(fx);
        int ry = (int) Math.round(fy);
        int rz = (int) Math.round(fz);
        double dx = Math.abs(rx - fx);
        double dy = Math.abs(ry - fy);
        double dz = Math.abs(rz - fz);
        if (dx > dy && dx > dz) rx = -ry - rz;
        else if (dy > dz) ry = -rx - rz;
        // rz is not reassigned — the else branch is a no-op for the return value
        return new int[] {rx, ry};
    }

    private static int hexDist(int aq, int ar, int bq, int br) {
        return (Math.abs(aq - bq) + Math.abs(ar - br) + Math.abs(-aq - ar + bq + br)) / 2;
    }
}

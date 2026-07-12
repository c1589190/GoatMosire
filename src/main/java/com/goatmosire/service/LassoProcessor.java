package com.goatmosire.service;

import com.gsim.map.MapData;
import java.util.*;

/**
 * Validates and auto-closes lasso boundaries, then flood-fills interior.
 * Moved from frontend to server to guarantee correct closure.
 */
public final class LassoProcessor {

    private LassoProcessor() {}

    private static final int[][] DIRS = {{1,0},{1,-1},{0,-1},{-1,0},{-1,1},{0,1}};
    private static final int MAX_RADIUS = 200;

    /**
     * From raw lasso hexKeys, build a closed wall, then flood fill interior.
     * @param rawKeys list of hex keys from lasso (e.g., "10_-5")
     * @return filled hex set, or empty if lasso is invalid
     */
    public static Set<String> fill(List<String> rawKeys) {
        if (rawKeys == null || rawKeys.size() < 3) return Collections.emptySet();

        // 1. Build closed wall: bridge gaps with hexLine
        Set<String> wall = new HashSet<>();
        for (String k : rawKeys) wall.add(k);

        for (int i = 0; i < rawKeys.size(); i++) {
            String a = rawKeys.get(i);
            String b = rawKeys.get((i + 1) % rawKeys.size());
            for (String bridge : hexLine(a, b)) {
                wall.add(bridge);
            }
        }

        // 2. Find seed hex inside wall
        String seed = findSeed(rawKeys);
        if (seed == null || wall.contains(seed)) return Collections.emptySet();

        // 3. Flood fill from seed, bounded by wall
        Set<String> filled = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        stack.push(seed);
        int maxFill = 50000;

        while (!stack.isEmpty() && filled.size() < maxFill) {
            String key = stack.pop();
            if (visited.contains(key) || wall.contains(key)) continue;
            visited.add(key);
            filled.add(key);
            int[] qr = MapData.parseHexKey(key);
            if (Math.abs(qr[0]) > MAX_RADIUS || Math.abs(qr[1]) > MAX_RADIUS) continue;
            for (int[] d : DIRS) {
                String nk = (qr[0]+d[0]) + "_" + (qr[1]+d[1]);
                if (!visited.contains(nk) && !wall.contains(nk)) stack.push(nk);
            }
        }

        // Include wall hexes in the fill
        filled.addAll(wall);
        return filled;
    }

    /** Find a hex likely inside the lasso ring (centroid of raw keys). */
    private static String findSeed(List<String> rawKeys) {
        double sumQ = 0, sumR = 0;
        for (String k : rawKeys) {
            int[] qr = MapData.parseHexKey(k);
            sumQ += qr[0]; sumR += qr[1];
        }
        int q = (int) Math.round(sumQ / rawKeys.size());
        int r = (int) Math.round(sumR / rawKeys.size());
        return q + "_" + r;
    }

    /** Hex line between two axial hexes (Bresenham-style). */
    private static List<String> hexLine(String a, String b) {
        int[] qrA = MapData.parseHexKey(a);
        int[] qrB = MapData.parseHexKey(b);
        return hexLine(qrA[0], qrA[1], qrB[0], qrB[1]);
    }

    static List<String> hexLine(int aq, int ar, int bq, int br) {
        List<String> line = new ArrayList<>();
        int dist = hexDist(aq, ar, bq, br);
        if (dist == 0) { line.add(aq + "_" + ar); return line; }
        for (int i = 0; i <= dist; i++) {
            double t = dist == 0 ? 0 : (double) i / dist;
            int q = (int) Math.round(aq + (bq - aq) * t);
            int r = (int) Math.round(ar + (br - ar) * t);
            // Adjust for cube rounding
            int s = -q - r;
            double dq = Math.abs(aq + (bq - aq) * t - q);
            double dr = Math.abs(ar + (br - ar) * t - r);
            double ds = Math.abs((-aq-ar) + (-bq-br+aq+ar) * t - s);
            if (dq > dr && dq > ds) q = -r - s;
            else if (dr > ds) r = -q - s;
            line.add(q + "_" + r);
        }
        return line;
    }

    private static int hexDist(int aq, int ar, int bq, int br) {
        return (Math.abs(aq - bq) + Math.abs(ar - br) + Math.abs(-aq-ar + bq+br)) / 2;
    }
}

package com.goatmosire.service;

import com.gsim.map.MapData;
import java.util.*;

/**
 * Processes terrain blocks: auto-closes boundaries, merges same-terrain,
 * carves holes for different-terrain overlaps. All polygon geometry is
 * handled server-side — frontend only sends raw lasso points.
 *
 * @deprecated Replaced by {@link TerrainCanvas}. Retained for reference.
 */
@Deprecated
public class TerrainBlockProcessor {

    private final int radius;

    // Hex direction vectors (flat-top axial)
    private static final int[][] DIRS = {{1,0},{0,1},{-1,1},{-1,0},{0,-1},{1,-1}};

    public TerrainBlockProcessor(int radius) {
        this.radius = radius;
    }

    /**
     * Process a raw lasso polygon and update terrain blocks.
     * @param existingBlocks current list of blocks (will be modified)
     * @param rawPoints raw lasso points in pixel space [{x,y},...]
     * @param terrain terrain type to assign
     */
    public void process(List<MapData.TerrainBlock> existingBlocks,
                        List<MapData.Pt> rawPoints, String terrain) {

        // 1. Auto-close: ensure polygon is closed
        List<MapData.Pt> closed = closePolygon(rawPoints);

        // 2. Compute hex set covered by this polygon
        Set<String> newHexSet = hexSetFromPolygon(closed);

        // 3. Merge with same-terrain blocks
        List<MapData.TerrainBlock> merged = new ArrayList<>();
        Set<String> mergedHexSet = new HashSet<>(newHexSet);
        for (MapData.TerrainBlock existing : existingBlocks) {
            if (existing.terrain().equals(terrain)) {
                Set<String> es = hexSetFromPolygon(existing.boundary());
                // Check overlap
                boolean overlaps = es.stream().anyMatch(newHexSet::contains);
                if (overlaps) {
                    mergedHexSet.addAll(es);
                    continue; // skip — will be merged
                }
            }
            merged.add(existing);
        }

        // 4. Subtract from different-terrain blocks (carve holes)
        for (int i = 0; i < merged.size(); i++) {
            MapData.TerrainBlock existing = merged.get(i);
            if (!existing.terrain().equals(terrain)) {
                Set<String> es = hexSetFromPolygon(existing.boundary());
                es.removeAll(mergedHexSet);
                if (es.isEmpty()) {
                    merged.remove(i);
                    i--;
                } else {
                    merged.set(i, rebuildBlock(existing.terrain(), es));
                }
            }
        }

        // 5. Rebuild the merged union as a single block
        MapData.TerrainBlock newBlock = rebuildBlock(terrain, mergedHexSet);
        merged.add(newBlock);

        // 6. Update list
        existingBlocks.clear();
        existingBlocks.addAll(merged);
    }

    /** Query which terrain covers a hex coordinate (latest block wins) */
    public String queryTerrain(List<MapData.TerrainBlock> blocks, int q, int r) {
        double px = q + r * 0.5;
        double py = r * 0.8660254;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            if (pointInPolygon(px, py, blocks.get(i).boundary())) {
                return blocks.get(i).terrain();
            }
        }
        return "water";
    }

    // ── Geometry ──────────────────────────────────────────

    private List<MapData.Pt> closePolygon(List<MapData.Pt> pts) {
        if (pts.size() < 3) return pts;
        MapData.Pt first = pts.get(0);
        MapData.Pt last = pts.get(pts.size() - 1);
        if (Math.abs(first.x() - last.x()) < 0.01 && Math.abs(first.y() - last.y()) < 0.01) {
            return pts; // already closed
        }
        List<MapData.Pt> closed = new ArrayList<>(pts);
        closed.add(new MapData.Pt(first.x(), first.y()));
        return closed;
    }

    private Set<String> hexSetFromPolygon(List<MapData.Pt> boundary) {
        Set<String> hexSet = new HashSet<>();
        if (boundary.size() < 3) return hexSet;

        // Find centroid as seed
        double cx = 0, cy = 0;
        for (MapData.Pt p : boundary) { cx += p.x(); cy += p.y(); }
        cx /= boundary.size(); cy /= boundary.size();

        // Convert pixel to axial
        int[] seedHex = pixelToHex(cx, cy);
        String seedKey = seedHex[0] + "_" + seedHex[1];

        // Flood fill
        Deque<String> stack = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        stack.push(seedKey);

        while (!stack.isEmpty()) {
            String key = stack.pop();
            if (visited.contains(key)) continue;
            visited.add(key);
            int[] qr = MapData.parseHexKey(key);
            double px = qr[0] + qr[1] * 0.5;
            double py = qr[1] * 0.8660254;
            if (!pointInPolygon(px, py, boundary)) continue;
            hexSet.add(key);
            for (int[] d : DIRS) {
                int nq = qr[0] + d[0], nr = qr[1] + d[1];
                if (Math.abs(nq) < radius * 2 && Math.abs(nr) < radius * 2) {
                    String nk = nq + "_" + nr;
                    if (!visited.contains(nk)) stack.push(nk);
                }
            }
        }
        return hexSet;
    }

    private boolean pointInPolygon(double px, double py, List<MapData.Pt> poly) {
        boolean inside = false;
        int n = poly.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = poly.get(i).x(), yi = poly.get(i).y();
            double xj = poly.get(j).x(), yj = poly.get(j).y();
            if ((yi > py) != (yj > py) &&
                px < (xj - xi) * (py - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    private MapData.TerrainBlock rebuildBlock(String terrain, Set<String> hexSet) {
        List<MapData.Pt> boundary = hexSetToBoundary(hexSet);
        String seed = hexSet.isEmpty() ? "0_0" : hexSet.iterator().next();
        return new MapData.TerrainBlock(terrain, boundary, seed);
    }

    private List<MapData.Pt> hexSetToBoundary(Set<String> hexSet) {
        if (hexSet.isEmpty()) return List.of();
        List<MapData.Pt> pts = new ArrayList<>();
        for (String key : hexSet) {
            int[] qr = MapData.parseHexKey(key);
            boolean isEdge = false;
            for (int[] d : DIRS) {
                if (!hexSet.contains((qr[0]+d[0]) + "_" + (qr[1]+d[1]))) { isEdge = true; break; }
            }
            if (isEdge) {
                double[] pixel = hexToPixel(qr[0], qr[1]);
                pts.add(new MapData.Pt(pixel[0], pixel[1]));
            }
        }
        if (pts.size() < 3) return pts;
        // Sort by angle around centroid
        double cx = pts.stream().mapToDouble(MapData.Pt::x).average().orElse(0);
        double cy = pts.stream().mapToDouble(MapData.Pt::y).average().orElse(0);
        pts.sort((a, b) -> Double.compare(
            Math.atan2(a.y() - cy, a.x() - cx),
            Math.atan2(b.y() - cy, b.x() - cx)));
        List<MapData.Pt> result = new ArrayList<>(pts);
        result.add(new MapData.Pt(pts.get(0).x(), pts.get(0).y()));
        return result;
    }

    // ── Coordinate conversion ─────────────────────────────

    private static final double GRID = 30.0;

    private int[] pixelToHex(double x, double y) {
        double q = (Math.sqrt(3)/3 * x - 1.0/3 * y) / GRID;
        double r = (2.0/3 * y) / GRID;
        return hexRound(q, r);
    }

    private double[] hexToPixel(int q, int r) {
        return new double[]{
            GRID * (Math.sqrt(3) * q + Math.sqrt(3)/2 * r),
            GRID * (3.0/2 * r)
        };
    }

    private int[] hexRound(double fq, double fr) {
        double fs = -fq - fr;
        int q = (int) Math.round(fq);
        int r = (int) Math.round(fr);
        int s = (int) Math.round(fs);
        double dq = Math.abs(q - fq), dr = Math.abs(r - fr), ds = Math.abs(s - fs);
        if (dq > dr && dq > ds) q = -r - s;
        else if (dr > ds) r = -q - s;
        return new int[]{q, r};
    }
}

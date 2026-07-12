package com.goatmosire.service;

import com.gsim.map.MapData;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages a collection of terrain blocks for a single world.
 *
 * <h3>Overlap rules</h3>
 * <b>Same terrain:</b>
 * <ul>
 *   <li>Big fully contains small → small deleted</li>
 *   <li>Overlap or adjacent → merged (geometry union)</li>
 * </ul>
 * <b>Different terrain:</b>
 * <ul>
 *   <li>Small inside big → big hollowed (difference) to make room</li>
 *   <li>Big over small → maximum retention: small kept, big gets inner boundary hole</li>
 *   <li>To truly override: draw a same-terrain block fully encircling the target</li>
 * </ul>
 *
 * <p>Query order: for any hex, the <em>last</em> (topmost) block that contains
 * it determines the terrain. Returns {@code null} for empty canvas.
 */
public class TerrainCanvas {

    private static final int DEFAULT_MAP_RADIUS = 80;

    /** Managed block with mutable cache. */
    static class Block {
        final String id;
        final String terrain;
        volatile List<MapData.Pt> boundary;       // polygon in pixel coords
        final String seedKey;
        final long createdAt;

        /** Cached hex set — lazily computed, invalidated on geometry change. */
        volatile Set<String> hexSet;

        Block(String id, String terrain, List<MapData.Pt> boundary, String seedKey) {
            this.id = id;
            this.terrain = terrain;
            this.boundary = new ArrayList<>(boundary);
            this.seedKey = seedKey;
            this.createdAt = System.currentTimeMillis();
        }

        Set<String> hexSet(int mapRadius) {
            Set<String> hs = hexSet;
            if (hs == null) {
                synchronized (this) {
                    hs = hexSet;
                    if (hs == null) {
                        hs = TerrainGeometry.hexSetFromPolygon(boundary, mapRadius, seedKey);
                        hexSet = hs;
                    }
                }
            }
            return hs;
        }

        void invalidateCache() { hexSet = null; }

        MapData.TerrainBlock toGsimBlock(int mapRadius) {
            Set<String> hs = hexSet(mapRadius);
            // Only compute boundary if hexSet is small; otherwise skip (use empty boundary)
            List<MapData.Pt> bnd = hs.size() <= 500 ? List.copyOf(boundary) : List.of();
            return new MapData.TerrainBlock(terrain, bnd, seedKey, hs);
        }
    }

    // ── State ──────────────────────────────────────────────────

    private final List<Block> blocks = new ArrayList<>();
    private final int mapRadius;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public TerrainCanvas() { this(DEFAULT_MAP_RADIUS); }
    public TerrainCanvas(int mapRadius) { this.mapRadius = mapRadius; }

    // ── Query ──────────────────────────────────────────────────

    /**
     * Return the terrain attribute for a hex, or {@code null} for empty.
     * Topmost (last) block that contains the hex wins.
     */
    public String queryHex(int q, int r) {
        double[] px = TerrainGeometry.hexToPixel(q, r);
        return queryPoint(px[0], px[1]);
    }

    /**
     * Return the terrain attribute for a pixel point, or {@code null} for empty.
     */
    public String queryPixel(double px, double py) {
        return queryPoint(px, py);
    }

    private String queryPoint(double px, double py) {
        lock.readLock().lock();
        try {
            for (int i = blocks.size() - 1; i >= 0; i--) {
                Block b = blocks.get(i);
                if (b.hexSet(mapRadius).contains(keyForPoint(px, py, b))) {
                    return b.terrain;
                }
            }
            return null; // empty
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Try hexKey from point, but prefer to check hexSet membership directly. */
    private String keyForPoint(double px, double py, Block b) {
        int[] h = TerrainGeometry.pixelToHex(px, py);
        return TerrainGeometry.hexKey(h[0], h[1]);
    }

    /** Efficient bulk query: check if a hex center is inside any block. */
    private boolean anyBlockContains(int q, int r) {
        double px = q + r * 0.5;
        double py = r * 0.8660254;
        lock.readLock().lock();
        try {
            for (int i = blocks.size() - 1; i >= 0; i--) {
                Block b = blocks.get(i);
                String key = TerrainGeometry.hexKey(q, r);
                if (b.hexSet(mapRadius).contains(key)) return true;
            }
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ── Mutation ───────────────────────────────────────────────

    /**
     * Add a block with full overlap processing.
     *
     * @param terrain  terrain attribute (e.g. "mountain", "plains")
     * @param boundary polygon boundary in pixel coordinates
     * @param seedKey  preferred seed hex (or empty)
     * @return the new block id, or null if the block was empty after overlap processing
     */
    public String addBlock(String terrain, List<MapData.Pt> boundary, String seedKey) {
        if (terrain == null || boundary == null || boundary.size() < 3) return null;
        Set<String> newSet = TerrainGeometry.hexSetFromPolygon(boundary, mapRadius, seedKey);
        if (newSet.isEmpty()) return null;
        return addBlockInternal(terrain, newSet, seedKey);
    }

    /** Add a block from pre-computed hex set (client-side flood fill). */
    public String addBlockFromHexSet(String terrain, Set<String> hexSet, String seedKey) {
        if (terrain == null || hexSet == null || hexSet.isEmpty()) return null;
        return addBlockInternal(terrain, hexSet, seedKey);
    }

    private String addBlockInternal(String terrain, Set<String> newSet, String seedKey) {
        boolean didMerge = false;
        List<Block> toRemove = new ArrayList<>();

        lock.writeLock().lock();
        try {
            // ── Phase A: same-terrain merge ──
            for (Block existing : new ArrayList<>(blocks)) {
                if (!existing.terrain.equals(terrain)) continue;
                Set<String> es = existing.hexSet(mapRadius);

                if (newSet.containsAll(es)) {
                    // Fully contained → remove small
                    toRemove.add(existing);
                } else if (TerrainGeometry.overlapsOrAdjacent(newSet, es)) {
                    // Merge: union hex sets
                    newSet.addAll(es);
                    toRemove.add(existing);
                    didMerge = true;
                }
            }
            blocks.removeAll(toRemove);
            toRemove.clear();

            // ── Phase B: different-terrain → new block wins, carve old ──
            List<Block> newFragments = new ArrayList<>();
            for (Block existing : new ArrayList<>(blocks)) {
                if (existing.terrain.equals(terrain)) continue;
                Set<String> es = existing.hexSet(mapRadius);

                Set<String> overlap = new HashSet<>(es);
                overlap.retainAll(newSet);
                if (overlap.isEmpty()) continue;

                es.removeAll(overlap);
                toRemove.add(existing);

                if (!es.isEmpty()) {
                    // Split remaining hexes into connected components
                    List<Set<String>> components = splitComponents(es);
                    for (Set<String> comp : components) {
                        List<MapData.Pt> bnd = comp.size() <= 500
                            ? TerrainGeometry.hexSetToBoundary(comp) : List.of();
                        Block frag = new Block(UUID.randomUUID().toString(), existing.terrain, bnd, existing.seedKey);
                        frag.hexSet = comp;
                        newFragments.add(frag);
                    }
                }
            }
            blocks.removeAll(toRemove);
            blocks.addAll(newFragments);
            toRemove.clear();

            // ── Phase D: add the new block ──
            if (newSet.isEmpty()) return null;

            String id = UUID.randomUUID().toString();
            // Only compute boundary for small sets; large sets skip polygon
            List<MapData.Pt> newBoundary = newSet.size() <= 500
                ? TerrainGeometry.hexSetToBoundary(newSet) : List.of();
            if (newSet.size() <= 500 && newBoundary.size() < 3) return null;

            Block newBlock = new Block(id, terrain, newBoundary, seedKey);
            newBlock.hexSet = newSet; // pre-warm cache
            blocks.add(newBlock);
            return id;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Remove a block by id. */
    public boolean removeBlock(String id) {
        lock.writeLock().lock();
        try {
            return blocks.removeIf(b -> b.id.equals(id));
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ── Serialization bridge ───────────────────────────────────

    /** Export all blocks as gsim-core TerrainBlock records for persistence. */
    public List<MapData.TerrainBlock> getBlocks() {
        lock.readLock().lock();
        try {
            List<MapData.TerrainBlock> result = new ArrayList<>();
            for (Block b : blocks) {
                result.add(b.toGsimBlock(mapRadius));
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Split a hex set into connected components (by hex adjacency). */
    private static List<Set<String>> splitComponents(Set<String> hexSet) {
        List<Set<String>> result = new ArrayList<>();
        Set<String> remaining = new HashSet<>(hexSet);
        while (!remaining.isEmpty()) {
            String seed = remaining.iterator().next();
            remaining.remove(seed);
            Set<String> comp = new HashSet<>();
            Deque<String> stack = new ArrayDeque<>();
            stack.push(seed);
            while (!stack.isEmpty()) {
                String key = stack.pop();
                comp.add(key);
                int[] h = MapData.parseHexKey(key);
                for (int[] d : TerrainGeometry.DIRS) {
                    String nk = MapData.hexKey(h[0] + d[0], h[1] + d[1]);
                    if (remaining.remove(nk)) stack.push(nk);
                }
            }
            result.add(comp);
        }
        return result;
    }

    /** Load blocks from gsim-core records (replaces canvas state). */
    public void setBlocks(List<MapData.TerrainBlock> gsimBlocks) {
        lock.writeLock().lock();
        try {
            blocks.clear();
            if (gsimBlocks == null) return;
            for (int i = 0; i < gsimBlocks.size(); i++) {
                MapData.TerrainBlock gb = gsimBlocks.get(i);
                String id = "b" + i + "_" + UUID.randomUUID().toString().substring(0, 8);
                String seedKey = gb.seedKey() != null ? gb.seedKey() : "";
                Block b = new Block(id, gb.terrain(), gb.boundary(), seedKey);
                // Pre-warm hexSet from loaded hexKeys (skip boundary polygon)
                if (!gb.hexKeys().isEmpty()) {
                    b.hexSet = new HashSet<>(gb.hexKeys());
                }
                blocks.add(b);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isEmpty() {
        lock.readLock().lock();
        try { return blocks.isEmpty(); }
        finally { lock.readLock().unlock(); }
    }

    public int size() {
        lock.readLock().lock();
        try { return blocks.size(); }
        finally { lock.readLock().unlock(); }
    }

    public int getMapRadius() { return mapRadius; }
}

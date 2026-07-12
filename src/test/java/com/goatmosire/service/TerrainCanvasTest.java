package com.goatmosire.service;

import com.gsim.map.MapData;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

/**
 * Unit tests for {@link TerrainCanvas} overlap rules and hex query.
 *
 * <p>Test scenarios:
 * <ol>
 *   <li>Same terrain: big contains small → small deleted</li>
 *   <li>Same terrain: overlap → merged</li>
 *   <li>Different terrain: small inside big → big hollowed</li>
 *   <li>Different terrain: big over small → max retention (small kept)</li>
 *   <li>Different terrain: override via same-terrain merge + full containment</li>
 *   <li>Query: empty canvas returns null</li>
 *   <li>Query: returns correct topmost terrain</li>
 * </ol>
 */
public class TerrainCanvasTest {

    private static final double S = TerrainGeometry.SIZE;

    /** Create a square-ish polygon in pixel space around hex center (q, r) extending outward by hexR. */
    static List<MapData.Pt> rectPoly(int q, int r, int hexR) {
        double[] tl = TerrainGeometry.hexToPixel(q - hexR, r - hexR);
        double[] tr = TerrainGeometry.hexToPixel(q + hexR, r - hexR);
        double[] br = TerrainGeometry.hexToPixel(q + hexR, r + hexR);
        double[] bl = TerrainGeometry.hexToPixel(q - hexR, r + hexR);
        return List.of(
            new MapData.Pt(tl[0] - 10, tl[1] - 10),
            new MapData.Pt(tr[0] + 10, tr[1] - 10),
            new MapData.Pt(br[0] + 10, br[1] + 10),
            new MapData.Pt(bl[0] - 10, bl[1] + 10),
            new MapData.Pt(tl[0] - 10, tl[1] - 10) // close
        );
    }

    /** Quick hex key. */
    static String hk(int q, int r) { return q + "_" + r; }

    // ══════════════════════════════════════════════════════════

    @Test
    void testEmptyCanvasReturnsNull() {
        TerrainCanvas canvas = new TerrainCanvas();
        assertTrue(canvas.isEmpty());
        assertNull(canvas.queryHex(0, 0));
        assertNull(canvas.queryHex(10, 20));
    }

    @Test
    void testAddBlockReturnsId() {
        TerrainCanvas canvas = new TerrainCanvas();
        String id = canvas.addBlock("plains", rectPoly(0, 0, 3), hk(0, 0));
        assertNotNull(id);
        assertEquals(1, canvas.size());
        assertFalse(canvas.isEmpty());
    }

    @Test
    void testQueryReturnsCorrectTerrain() {
        TerrainCanvas canvas = new TerrainCanvas();
        canvas.addBlock("plains", rectPoly(0, 0, 3), hk(0, 0));
        assertEquals("plains", canvas.queryHex(0, 0));
        assertEquals("plains", canvas.queryHex(1, 0));
        assertEquals("plains", canvas.queryHex(0, 1));
    }

    @Test
    void testQueryTopmostBlockWins() {
        // Add two different-terrain blocks at same location; last one wins
        TerrainCanvas canvas = new TerrainCanvas();
        canvas.addBlock("plains", rectPoly(0, 0, 3), hk(0, 0));
        canvas.addBlock("mountain", rectPoly(0, 0, 1), hk(0, 0));

        // Center should be mountain (topmost)
        assertEquals("mountain", canvas.queryHex(0, 0));
        // Edges of the larger block should still be plains
        assertEquals("plains", canvas.queryHex(2, 2));
    }

    // ═══════════════ SAME-TERRAIN RULES ═══════════════════════

    @Test
    void testSameTerrainFullContainmentRemovesSmall() {
        TerrainCanvas canvas = new TerrainCanvas();
        canvas.addBlock("plains", rectPoly(0, 0, 10), hk(0, 0)); // big block

        // Small block fully inside big
        canvas.addBlock("plains", rectPoly(0, 0, 2), hk(0, 0));

        // Should have 1 block (small was removed during merge)
        assertEquals(1, canvas.size(), "Small same-terrain block should be deleted when fully contained");
        assertEquals("plains", canvas.queryHex(0, 0));
    }

    @Test
    void testSameTerrainPartialOverlapMerges() {
        TerrainCanvas canvas = new TerrainCanvas();
        canvas.addBlock("plains", rectPoly(0, 0, 2), hk(0, 0));
        assertEquals(1, canvas.size());

        // Add adjacent/overlapping block with slightly different location
        canvas.addBlock("plains", rectPoly(1, 0, 2), hk(1, 0));

        // Should be 1 block after merge (union)
        assertEquals(1, canvas.size(), "Overlapping same-terrain blocks should merge");
        assertEquals("plains", canvas.queryHex(0, 0));
        assertEquals("plains", canvas.queryHex(1, 0));
    }

    // ═══════════════ DIFFERENT-TERRAIN RULES ══════════════════

    @Test
    void testDifferentTerrainSmallInsideBigHollowsBig() {
        TerrainCanvas canvas = new TerrainCanvas();
        canvas.addBlock("mountain", rectPoly(0, 0, 8), hk(0, 0));
        assertEquals(1, canvas.size());

        // Draw small different-terrain block inside
        canvas.addBlock("plains", rectPoly(0, 0, 2), hk(0, 0));

        // Both blocks should survive: mountain (hollowed) and plains (inside)
        assertEquals(2, canvas.size(), "Both blocks should exist after hollow");
        assertEquals("plains", canvas.queryHex(0, 0), "Center should be plains");
        assertEquals("mountain", canvas.queryHex(5, 0), "Outer area should still be mountain");
    }

    @Test
    void testDifferentTerrainBigOverSmallMaxRetention() {
        TerrainCanvas canvas = new TerrainCanvas();
        // First: small plains
        canvas.addBlock("plains", rectPoly(0, 0, 2), hk(0, 0));
        assertEquals(1, canvas.size());

        // Then: big mountain over it — max retention: plains stays, mountain hollowed
        canvas.addBlock("mountain", rectPoly(0, 0, 8), hk(0, 0));

        assertEquals(2, canvas.size(), "Both blocks should exist (max retention)");
        assertEquals("plains", canvas.queryHex(0, 0), "Small plains should be preserved");
        assertEquals("mountain", canvas.queryHex(5, 0));
    }

    @Test
    void testOverrideSmallBlockBySameTerrainEncircling() {
        TerrainCanvas canvas = new TerrainCanvas();
        // Big mountain
        canvas.addBlock("mountain", rectPoly(0, 0, 10), hk(0, 0));
        // Small plains inside
        canvas.addBlock("plains", rectPoly(0, 0, 2), hk(0, 0));
        assertEquals(2, canvas.size());

        // NOW: draw a MOUNTAIN block encircling the plains area
        // Same terrain as outer mountain → merges with outer
        // Fully contains plains with same-terrain merge active → OVERRIDE deletes plains
        canvas.addBlock("mountain", rectPoly(0, 0, 3), hk(0, 0));

        assertEquals(1, canvas.size(), "Plains should be deleted via override");
        assertEquals("mountain", canvas.queryHex(0, 0), "Center should now be mountain");
    }

    @Test
    void testNoOverrideWhenDifferentTerrainDoesNotMerge() {
        TerrainCanvas canvas = new TerrainCanvas();
        // Just a small plains, no outer block
        canvas.addBlock("plains", rectPoly(0, 0, 2), hk(0, 0));
        assertEquals(1, canvas.size());

        // Big mountain over it — but no outer mountain to merge with
        canvas.addBlock("mountain", rectPoly(0, 0, 8), hk(0, 0));

        // Both survive: max retention (no merge → no override trigger)
        assertEquals(2, canvas.size());
        assertEquals("plains", canvas.queryHex(0, 0));
    }

    @Test
    void testRemoveBlock() {
        TerrainCanvas canvas = new TerrainCanvas();
        String id = canvas.addBlock("plains", rectPoly(0, 0, 3), hk(0, 0));
        assertNotNull(id);
        assertEquals(1, canvas.size());

        boolean removed = canvas.removeBlock(id);
        assertTrue(removed);
        assertEquals(0, canvas.size());
        assertTrue(canvas.isEmpty());
    }

    @Test
    void testSetBlocksFromGsimBlocks() {
        TerrainCanvas canvas = new TerrainCanvas();
        List<MapData.TerrainBlock> gsimBlocks = List.of(
            new MapData.TerrainBlock("plains", rectPoly(0, 0, 3), hk(0, 0)),
            new MapData.TerrainBlock("forest", rectPoly(10, 0, 2), hk(10, 0))
        );
        canvas.setBlocks(gsimBlocks);
        assertEquals(2, canvas.size());
        assertEquals("plains", canvas.queryHex(0, 0));
        assertEquals("forest", canvas.queryHex(10, 0));
    }

    // ═══════════════ GEOMETRY UNIT TESTS ══════════════════════

    @Test
    void testHexToPixelRoundTrip() {
        for (int q = -10; q <= 10; q++) {
            for (int r = -10; r <= 10; r++) {
                double[] px = TerrainGeometry.hexToPixel(q, r);
                int[] back = TerrainGeometry.pixelToHex(px[0], px[1]);
                assertEquals(q, back[0], "q mismatch at (" + q + "," + r + ")");
                assertEquals(r, back[1], "r mismatch at (" + q + "," + r + ")");
            }
        }
    }

    @Test
    void testHexSetFromPolygon() {
        List<MapData.Pt> poly = rectPoly(0, 0, 2);
        Set<String> hexes = TerrainGeometry.hexSetFromPolygon(poly, 80, hk(0, 0));
        assertFalse(hexes.isEmpty(), "Should find hexes inside polygon");
        assertTrue(hexes.contains(hk(0, 0)), "Center hex should be in set");
    }

    @Test
    void testHexSetToBoundaryRoundTrip() {
        List<MapData.Pt> poly = rectPoly(0, 0, 3);
        Set<String> hexes = TerrainGeometry.hexSetFromPolygon(poly, 80, hk(0, 0));
        assertFalse(hexes.isEmpty());
        List<MapData.Pt> boundary = TerrainGeometry.hexSetToBoundary(hexes);
        assertTrue(boundary.size() >= 4, "Boundary should have at least 4 points");
    }

    @Test
    void testIntersectsAndAdjacent() {
        // Overlapping sets
        Set<String> a = new HashSet<>(Set.of(hk(0, 0), hk(1, 0), hk(0, 1)));
        Set<String> b = new HashSet<>(Set.of(hk(1, 0), hk(2, 0), hk(1, 1)));
        assertTrue(TerrainGeometry.intersects(a, b));
        assertTrue(TerrainGeometry.overlapsOrAdjacent(a, b));

        // Adjacent but not overlapping
        Set<String> c = new HashSet<>(Set.of(hk(0, 0)));
        Set<String> d = new HashSet<>(Set.of(hk(1, 0)));
        assertFalse(TerrainGeometry.intersects(c, d));
        assertTrue(TerrainGeometry.isAdjacent(c, d));
        assertTrue(TerrainGeometry.overlapsOrAdjacent(c, d));

        // Far apart
        Set<String> e = new HashSet<>(Set.of(hk(0, 0)));
        Set<String> f = new HashSet<>(Set.of(hk(100, 100)));
        assertFalse(TerrainGeometry.intersects(e, f));
        assertFalse(TerrainGeometry.isAdjacent(e, f));
        assertFalse(TerrainGeometry.overlapsOrAdjacent(e, f));
    }
}

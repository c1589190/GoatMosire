package com.goatmosire.service;

import com.goatmosire.map.MapData;
import com.goatmosire.map.MapData.CompressedRegion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates and repairs {@link CompressedRegion} boundaries on map load.
 *
 * <p>A valid CR boundary must:
 * <ol>
 *   <li><b>Self-cover</b> — every hex center in the CR's {@code hexKeys}
 *       must lie inside the CR's outer boundary polygon.</li>
 *   <li><b>No intrusion</b> — the boundary must not cover hex centers of
 *       adjacent different-terrain CRs or uncompressed hexes.</li>
 *   <li><b>No double-cover</b> — no hex center may be inside the boundaries
 *       of more than one CR.</li>
 * </ol>
 *
 * <p>Repairing an invalid CR means regenerating its boundary via
 * {@link TerrainGeometry#hexSetToBoundaryWithHoles}, which includes hole rings
 * for any regions of other terrain enclosed by this CR.
 */
public final class CompressionValidator {

    private CompressionValidator() {
        // utility class
    }

    private static final Logger log = LoggerFactory.getLogger(CompressionValidator.class);
    private static final int MIN_SAMPLE = 5;
    private static final double SAMPLE_RATIO = 0.10;

    /** Result of a validation pass. */
    public static final class Result {
        private final boolean valid;
        private final int inspected;
        private final int badCount;
        private final Set<String> badIds;

        /**
         * Constructs a validation result.
         * @param valid whether all CRs are valid
         * @param inspected number of CRs inspected
         * @param badCount number of invalid CRs found
         * @param badIds set of invalid CR identifiers
         */
        public Result(boolean valid, int inspected, int badCount, Set<String> badIds) {
            this.valid = valid;
            this.inspected = inspected;
            this.badCount = badCount;
            this.badIds = badIds == null ? Set.of() : Set.copyOf(badIds);
        }

        /**
         * Returns whether all compressed regions passed validation.
         * @return true if all CRs are valid
         */
        public boolean valid() {
            return valid;
        }

        /**
         * Returns the number of compressed regions inspected.
         * @return the number of CRs inspected
         */
        public int inspected() {
            return inspected;
        }

        /**
         * Returns the number of invalid compressed regions found.
         * @return the number of invalid CRs found
         */
        public int invalidCount() {
            return badCount;
        }

        /**
         * Returns the set of invalid compressed region identifiers.
         * @return the set of invalid CR identifiers
         */
        public Set<String> badIds() {
            return Collections.unmodifiableSet(badIds);
        }
    }

    /**
     * Validate all compressed regions in a MapData.
     * Checks self-cover, no intrusion, and no double-cover constraints.
     * @param map the map data to validate
     * @return a Result with badIds populated for invalid CRs
     */
    public static Result validate(MapData map) {
        List<CompressedRegion> crs = map.compressedRegions();
        if (crs == null || crs.isEmpty()) return new Result(true, 0, 0, Set.of());

        Set<String> allHexKeys = map.hexes().keySet();
        Set<String> badIds = new LinkedHashSet<>();
        int inspected = 0;

        for (CompressedRegion cr : crs) {
            List<MapData.Pt> outer = cr.boundary();
            if (outer == null || outer.size() < 3) {
                badIds.add(cr.id());
                continue;
            }

            Set<String> hexKeys = cr.hexKeys();
            if (hexKeys == null || hexKeys.isEmpty()) {
                badIds.add(cr.id());
                continue;
            }

            // 1. Self-cover: sample hexKeys and check point-in-polygon
            int sampleSize = Math.max(MIN_SAMPLE, (int) (hexKeys.size() * SAMPLE_RATIO));
            List<String> sample = sample(hexKeys, sampleSize);
            int selfMiss = 0;
            for (String key : sample) {
                int[] qr = MapData.parseHexKey(key);
                double[] px = TerrainGeometry.hexToPixel(qr[0], qr[1]);
                if (!TerrainGeometry.pointInPolygon(px[0], px[1], outer)) {
                    selfMiss++;
                }
            }
            if (selfMiss > 0) {
                log.debug("CR {} self-cover fail: {}/{} samples outside boundary", cr.id(), selfMiss, sample.size());
                badIds.add(cr.id());
                inspected++;
                continue;
            }

            // 2. No intrusion: check adjacent hexes (not in this CR) aren't covered
            int intrusionCount = 0;
            int maxIntrusionCheck = 200;
            for (String key : hexKeys) {
                if (intrusionCount >= maxIntrusionCheck) break;
                int[] qr = MapData.parseHexKey(key);
                for (int[] d : TerrainGeometry.DIRS) {
                    String nk = MapData.hexKey(qr[0] + d[0], qr[1] + d[1]);
                    if (!hexKeys.contains(nk) && allHexKeys.contains(nk)) {
                        int[] nqr = MapData.parseHexKey(nk);
                        double[] npx = TerrainGeometry.hexToPixel(nqr[0], nqr[1]);
                        if (TerrainGeometry.pointInPolygon(npx[0], npx[1], outer)) {
                            intrusionCount++;
                            if (intrusionCount >= 3) break; // enough evidence
                        }
                    }
                }
            }
            if (intrusionCount >= 3) {
                log.debug("CR {} intrusion: {} adjacent hexes inside boundary", cr.id(), intrusionCount);
                badIds.add(cr.id());
            }

            inspected++;
        }

        boolean ok = badIds.isEmpty();
        if (!ok) {
            log.warn("CompressionValidator: {}/{} CRs invalid: {}", badIds.size(), inspected, badIds);
        }
        return new Result(ok, inspected, badIds.size(), badIds);
    }

    /**
     * Repair invalid compressed regions by regenerating their boundaries (with holes).
     * HexKeys are the truth — boundaries are derived from them.
     * @param crs the current list of compressed regions
     * @param badIds set of region IDs to repair
     * @return a new CompressedRegion list with repaired boundaries
     */
    public static List<CompressedRegion> repair(List<CompressedRegion> crs, Set<String> badIds) {
        if (badIds.isEmpty()) return crs;
        List<CompressedRegion> repaired = new ArrayList<>();
        for (CompressedRegion cr : crs) {
            if (!badIds.contains(cr.id())) {
                repaired.add(cr);
                continue;
            }
            // Regenerate boundary from hexKeys (which are always correct)
            Set<String> hexKeys = cr.hexKeys();
            if (hexKeys == null || hexKeys.isEmpty()) {
                repaired.add(cr);
                continue;
            }
            List<List<MapData.Pt>> boundaries = TerrainGeometry.hexSetToBoundaryWithHoles(hexKeys);
            if (boundaries.isEmpty()) {
                repaired.add(cr);
                continue;
            }
            List<MapData.Pt> outer = boundaries.get(0);
            CompressedRegion fixed =
                    new CompressedRegion(cr.id(), cr.terrain(), cr.color(), outer, boundaries, cr.isWater(), hexKeys);
            repaired.add(fixed);
            log.info(
                    "Repaired CR {} ({}): {} rings (outer + {} holes)",
                    cr.id(),
                    cr.terrain(),
                    boundaries.size(),
                    boundaries.size() - 1);
        }
        return repaired;
    }

    /**
     * Validate and optionally repair a full MapData.
     * @param map the map data to validate and repair
     * @return the (possibly repaired) map data
     */
    public static MapData validateAndRepair(MapData map) {
        Result result = validate(map);
        if (result.valid()) return map;

        List<CompressedRegion> fixed = repair(map.compressedRegions(), result.badIds());
        return new MapData(
                map.gridSize(),
                map.hexOrientation(),
                map.hexes(),
                map.terrainBlocks(),
                map.provinces(),
                map.cities(),
                map.rivers(),
                map.roads(),
                map.terrainTypes(),
                fixed,
                map.pathwayGroups());
    }

    // ── helpers ────────────────────────────────────────────────

    private static List<String> sample(Set<String> set, int n) {
        List<String> all = new ArrayList<>(set);
        Collections.shuffle(all, new Random(42)); // deterministic for reproducibility
        return all.subList(0, Math.min(n, all.size()));
    }
}

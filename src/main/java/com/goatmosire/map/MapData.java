package com.goatmosire.map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.*;

@JsonDeserialize
public record MapData(
    @JsonProperty("gridSize") int gridSize,
    @JsonProperty("hexOrientation") boolean hexOrientation,
    @JsonProperty("hexes") Map<String, HexCell> hexes,
    @JsonProperty("terrainBlocks") List<TerrainBlock> terrainBlocks,
    @JsonProperty("provinces") Map<String, Province> provinces,
    @JsonProperty("cities") Map<String, City> cities,
    @JsonProperty("rivers") @Deprecated List<River> rivers,
    @JsonProperty("roads") @Deprecated List<Road> roads,
    @JsonProperty("terrainTypes") Map<String, TerrainType> terrainTypes,
    @JsonProperty("compressedRegions") List<CompressedRegion> compressedRegions,
    @JsonProperty("pathwayGroups") Map<String, PathwayGroup> pathwayGroups
) {
    public MapData {
        if (gridSize < 1 || gridSize > 1000) throw new IllegalArgumentException("gridSize must be 1-1000, got: " + gridSize);
        if (hexes == null) hexes = new LinkedHashMap<>();
        if (terrainBlocks == null) terrainBlocks = new ArrayList<>();
        if (provinces == null) provinces = new LinkedHashMap<>();
        if (cities == null) cities = new LinkedHashMap<>();
        if (rivers == null) rivers = List.of();
        if (roads == null) roads = List.of();
        if (terrainTypes == null || terrainTypes.isEmpty()) terrainTypes = TerrainType.defaults();
        if (compressedRegions == null) compressedRegions = List.of();
        if (pathwayGroups == null || pathwayGroups.isEmpty()) pathwayGroups = defaultPathwayGroups();
    }

    public static MapData empty() {
        return new MapData(30, false, Map.of(), List.of(), Map.of(), Map.of(),
            List.of(), List.of(), TerrainType.defaults(), List.of(), defaultPathwayGroups());
    }

    public static String hexKey(int q, int r) { return q + "_" + r; }
    public static int[] parseHexKey(String key) {
        String[] parts = key.split("_");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    // ── TerrainBlock ──────────────────────────────────────

    @JsonDeserialize
    public record TerrainBlock(
        @JsonProperty("terrain") String terrain,
        @JsonProperty("boundary") List<Pt> boundary,
        @JsonProperty("seedKey") String seedKey,
        @JsonProperty("hexKeys") Set<String> hexKeys
    ) {
        public TerrainBlock {
            if (terrain == null) terrain = "plains";
            if (boundary == null) boundary = List.of();
            if (seedKey == null) seedKey = "";
            if (hexKeys == null) hexKeys = Set.of();
        }

        /** Constructor without hexKeys (derived from boundary). */
        public TerrainBlock(String terrain, List<Pt> boundary, String seedKey) {
            this(terrain, boundary, seedKey, Set.of());
        }
    }

    @JsonDeserialize
    public record Pt(@JsonProperty("x") double x, @JsonProperty("y") double y) {}

    // ── HexCell ────────────────────────────────────────────

    @JsonDeserialize
    public record HexCell(
        @JsonProperty("color") String color,
        @JsonProperty("terrain") String terrain,
        @JsonProperty("symbol") String symbol,
        @JsonProperty("symbolColor") String symbolColor,
        @JsonProperty("description") String description,
        @JsonProperty("riverMask") int riverMask,
        @JsonProperty("edgeTags") Map<Integer, List<String>> edgeTags
    ) {
        public HexCell {
            if (color == null) color = "#808080";
            if (terrain == null) terrain = "unknown";
            if (description == null) description = "";
            if (riverMask < 0 || riverMask > 63) riverMask = 0;
            if (edgeTags == null) edgeTags = new LinkedHashMap<>();
            // Migrate legacy riverMask to edgeTags
            if (edgeTags.isEmpty() && riverMask > 0) {
                for (int d = 0; d < 6; d++) {
                    if ((riverMask & (1 << d)) != 0) {
                        edgeTags.put(d, new ArrayList<>(List.of("river")));
                    }
                }
            }
        }
        public static HexCell of(String color) { return new HexCell(color, "unknown", null, null, "", 0, Map.of()); }
        public static HexCell of(String color, String terrain) { return new HexCell(color, terrain, null, null, "", 0, Map.of()); }
    }

    // ── TerrainType ────────────────────────────────────────

    @JsonDeserialize
    public record TerrainType(
        @JsonProperty("name") String name,
        @JsonProperty("color") String color,
        @JsonProperty("food") int food,
        @JsonProperty("gold") int gold,
        @JsonProperty("stone") int stone,
        @JsonProperty("moveCost") int moveCost,
        @JsonProperty("description") String description
    ) {
        public static Map<String, TerrainType> defaults() {
            var tt = new LinkedHashMap<String, TerrainType>();
            tt.put("water",    new TerrainType("水",   "#3295D2", 1, 0, 0, 99, "水域"));
            tt.put("plains",   new TerrainType("平原", "#6CC261", 3, 1, 1, 1,  "平原"));
            tt.put("forest",   new TerrainType("森林", "#228B22", 2, 1, 2, 2,  "森林"));
            tt.put("mountain", new TerrainType("山地", "#808080", 0, 2, 5, 3,  "山地"));
            tt.put("desert",   new TerrainType("沙漠", "#DDC88D", 1, 2, 1, 2,  "沙漠"));
            tt.put("swamp",    new TerrainType("沼泽", "#556B2F", 2, 0, 1, 2,  "沼泽"));
            tt.put("tundra",   new TerrainType("冻土", "#A8C4D8", 1, 1, 1, 2,  "冻土"));
            tt.put("hills",    new TerrainType("丘陵", "#BDB76B", 2, 1, 3, 2,  "丘陵"));
            return tt;
        }
    }

    // ── Province ───────────────────────────────────────────

    @JsonDeserialize
    public record Province(
        @JsonProperty("hexes") List<String> hexes,
        @JsonProperty("color") String color,
        @JsonProperty("tag") String tag,
        @JsonProperty("description") String description
    ) {
        public Province {
            if (hexes == null) hexes = List.of();
            if (color == null) color = "#ff0000";
            if (tag == null) tag = "";
            if (description == null) description = "";
        }
    }

    // ── City ───────────────────────────────────────────────

    @JsonDeserialize
    public record City(
        @JsonProperty("q") int q,
        @JsonProperty("r") int r,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description
    ) {
        public City {
            if (name == null) name = "";
            if (description == null) description = "";
        }
    }

    // ── River (deprecated) ──────────────────────────────────

    @JsonDeserialize @Deprecated
    public record River(
        @JsonProperty("name") String name,
        @JsonProperty("path") List<String> path,
        @JsonProperty("width") int width,
        @JsonProperty("color") String color
    ) {
        public River {
            if (name == null) name = "";
            if (path == null) path = List.of();
            if (width < 1) width = 3;
            if (color == null) color = "#3295D2";
        }
    }

    @JsonDeserialize @Deprecated
    public record Road(
        @JsonProperty("name") String name,
        @JsonProperty("path") List<String> path,
        @JsonProperty("width") int width,
        @JsonProperty("color") String color
    ) {
        public Road {
            if (name == null) name = "";
            if (path == null) path = List.of();
            if (width < 1) width = 2;
            if (color == null) color = "#f5deb3";
        }
    }

    // ── PathwayGroup ────────────────────────────────────────

    @JsonDeserialize
    public record PathwayGroup(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("color") String color,
        @JsonProperty("description") String description,
        @JsonProperty("visible") boolean visible
    ) {
        public PathwayGroup {
            if (id == null) id = "";
            if (name == null) name = "";
            if (color == null) color = "#808080";
            if (description == null) description = "";
        }
    }

    /** Default pathway groups: river and road. */
    public static Map<String, PathwayGroup> defaultPathwayGroups() {
        var g = new LinkedHashMap<String, PathwayGroup>();
        g.put("river", new PathwayGroup("river", "河流", "#3295D2", "天然水系", true));
        g.put("road", new PathwayGroup("road", "道路", "#8B7355", "陆路通道", true));
        return g;
    }

    // ── CompressedRegion ────────────────────────────────────

    /**
     * A compressed representation of a large contiguous same-terrain region.
     * Pure rendering optimization — does not replace hexes in {@link #hexes()}.
     * Can be regenerated at any time by re-running {@code compress()}.
     */
    @JsonDeserialize
    public record CompressedRegion(
        @JsonProperty("id") String id,
        @JsonProperty("terrain") String terrain,
        @JsonProperty("color") String color,
        @JsonProperty("boundary") @Deprecated List<Pt> boundary,
        @JsonProperty("boundaries") List<List<Pt>> boundaries,
        @JsonProperty("isWater") boolean isWater,
        @JsonProperty("hexKeys") Set<String> hexKeys
    ) {
        public CompressedRegion {
            if (id == null) id = "";
            if (terrain == null) terrain = "unknown";
            if (color == null) color = "#808080";
            // Backward compat: old data has only boundary (flat list, outer ring).
            // New data has boundaries (list of rings: outer + holes).
            // If boundaries is present and non-empty, use it; otherwise wrap old boundary.
            if (boundaries == null) boundaries = new ArrayList<>();
            if (boundaries.isEmpty() && boundary != null && !boundary.isEmpty()) {
                boundaries = List.of(boundary);
            }
            // Ensure boundary stays populated for old callers
            if (boundary == null) boundary = List.of();
            if (boundary.isEmpty() && !boundaries.isEmpty()) {
                boundary = boundaries.get(0); // outer ring
            }
            if (hexKeys == null) hexKeys = Set.of();
        }
        /** Derived from hexKeys size; not serialized. */
        public int size() { return hexKeys.size(); }
    }
}

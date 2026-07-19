package com.goatmosire.map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Root map data record holding all hex map state.
 *
 * @param gridSize          width/height of the hex grid (1-1000)
 * @param hexOrientation    true for pointy-top, false for flat-top
 * @param hexes             all hex cells keyed by "q_r"
 * @param terrainBlocks     ordered polygon blocks (last = topmost)
 * @param provinces         province definitions keyed by id
 * @param cities            city definitions keyed by id
 * @param rivers            deprecated river definitions
 * @param roads             deprecated road definitions
 * @param terrainTypes      terrain type definitions keyed by name
 * @param compressedRegions cached contour hulls for rendering
 * @param pathwayGroups     pathway group definitions (river, road, etc.)
 */
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
        @JsonProperty("pathwayGroups") Map<String, PathwayGroup> pathwayGroups) {
    public MapData {
        if (gridSize < 1 || gridSize > 1000)
            throw new IllegalArgumentException("gridSize must be 1-1000, got: " + gridSize);
        if (hexes == null) hexes = new LinkedHashMap<>();
        if (terrainBlocks == null) terrainBlocks = new ArrayList<>();
        if (provinces == null) provinces = new LinkedHashMap<>();
        if (cities == null) cities = new LinkedHashMap<>();
        if (rivers == null) rivers = List.of();
        if (roads == null) roads = List.of();
        if (terrainTypes == null || terrainTypes.isEmpty()) terrainTypes = TerrainType.defaults();
        if (compressedRegions == null) compressedRegions = List.of();
        if (pathwayGroups == null || pathwayGroups.isEmpty()) pathwayGroups = defaultPathwayGroups();
        // Defensive copy + freeze (SpotBugs EI_EXPOSE_REP)
        hexes = Map.copyOf(hexes);
        terrainBlocks = List.copyOf(terrainBlocks);
        provinces = Map.copyOf(provinces);
        cities = Map.copyOf(cities);
        terrainTypes = Map.copyOf(terrainTypes);
        compressedRegions = List.copyOf(compressedRegions);
        pathwayGroups = Map.copyOf(pathwayGroups);
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    @Override
    @Deprecated
    public List<River> rivers() {
        return this.rivers;
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    @Override
    @Deprecated
    public List<Road> roads() {
        return this.roads;
    }

    /**
     * Returns an empty MapData with default 30-size grid and default terrain types.
     *
     * @return a new empty MapData instance
     */
    public static MapData empty() {
        return new MapData(
                30,
                false,
                Map.of(),
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                TerrainType.defaults(),
                List.of(),
                defaultPathwayGroups());
    }

    /**
     * Creates a hex key string from axial coordinates.
     *
     * @param q the column coordinate
     * @param r the row coordinate
     * @return the hex key in "q_r" format
     */
    public static String hexKey(int q, int r) {
        return q + "_" + r;
    }

    /**
     * Parses a hex key string into axial coordinates.
     *
     * @param key the hex key in "q_r" format
     * @return an int array of [q, r]
     */
    public static int[] parseHexKey(String key) {
        String[] parts = key.split("_");
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    // ── TerrainBlock ──────────────────────────────────────

    /**
     * A terrain block — a contiguous area painted with a single terrain type.
     *
     * @param terrain  the terrain type identifier
     * @param boundary the polygon boundary points (outer ring)
     * @param seedKey  the seed hex key used during generation
     * @param hexKeys  the set of hex keys belonging to this block
     */
    @JsonDeserialize
    public record TerrainBlock(
            @JsonProperty("terrain") String terrain,
            @JsonProperty("boundary") List<Pt> boundary,
            @JsonProperty("seedKey") String seedKey,
            @JsonProperty("hexKeys") Set<String> hexKeys) {
        public TerrainBlock {
            if (terrain == null) terrain = "plains";
            if (boundary == null) boundary = List.of();
            if (seedKey == null) seedKey = "";
            if (hexKeys == null) hexKeys = Set.of();
            // Defensive copy + freeze (SpotBugs EI_EXPOSE_REP)
            boundary = List.copyOf(boundary);
            hexKeys = Set.copyOf(hexKeys);
        }

        /** Constructor without hexKeys (derived from boundary). */
        public TerrainBlock(String terrain, List<Pt> boundary, String seedKey) {
            this(terrain, boundary, seedKey, Set.of());
        }
    }

    /**
     * A 2D point with double precision coordinates.
     *
     * @param x the x-coordinate
     * @param y the y-coordinate
     */
    @JsonDeserialize
    public record Pt(@JsonProperty("x") double x, @JsonProperty("y") double y) {}

    // ── HexCell ────────────────────────────────────────────

    /**
     * A single hex cell with terrain, color, and metadata.
     *
     * @param color       the fill color
     * @param terrain     the terrain type identifier
     * @param symbol      optional symbol text
     * @param symbolColor color of the symbol text
     * @param description optional description
     * @param riverMask   6-bit edge mask (bits 0-5 for edges E,SE,SW,W,NW,NE)
     * @param edgeTags    per-edge tags keyed by direction (0-5)
     */
    @JsonDeserialize
    public record HexCell(
            @JsonProperty("color") String color,
            @JsonProperty("terrain") String terrain,
            @JsonProperty("symbol") String symbol,
            @JsonProperty("symbolColor") String symbolColor,
            @JsonProperty("description") String description,
            @JsonProperty("riverMask") int riverMask,
            @JsonProperty("edgeTags") Map<Integer, List<String>> edgeTags) {
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
            // Deep freeze inner lists and outer map (SpotBugs EI_EXPOSE_REP)
            Map<Integer, List<String>> frozen = new LinkedHashMap<>();
            for (var entry : edgeTags.entrySet()) {
                frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            edgeTags = Collections.unmodifiableMap(frozen);
        }

        /**
         * Creates a hex cell with the given color and default terrain.
         *
         * @param color the fill color
         * @return a new HexCell instance
         */
        public static HexCell of(String color) {
            return new HexCell(color, "unknown", null, null, "", 0, Map.of());
        }

        /**
         * Creates a hex cell with the given color and terrain.
         *
         * @param color   the fill color
         * @param terrain the terrain type
         * @return a new HexCell instance
         */
        public static HexCell of(String color, String terrain) {
            return new HexCell(color, terrain, null, null, "", 0, Map.of());
        }
    }

    // ── TerrainType ────────────────────────────────────────

    /**
     * Definition of a terrain type with resource yields and movement cost.
     *
     * @param name        display name
     * @param color       representative color
     * @param food        food yield
     * @param gold        gold yield
     * @param stone       stone yield
     * @param moveCost    movement cost modifier
     * @param description optional description
     */
    @JsonDeserialize
    public record TerrainType(
            @JsonProperty("name") String name,
            @JsonProperty("color") String color,
            @JsonProperty("food") int food,
            @JsonProperty("gold") int gold,
            @JsonProperty("stone") int stone,
            @JsonProperty("moveCost") int moveCost,
            @JsonProperty("description") String description) {
        /**
         * Returns the default set of terrain types.
         *
         * @return a map of terrain type names to TerrainType definitions
         */
        public static Map<String, TerrainType> defaults() {
            var tt = new LinkedHashMap<String, TerrainType>();
            tt.put("water", new TerrainType("水", "#3295D2", 1, 0, 0, 99, "水域"));
            tt.put("plains", new TerrainType("平原", "#6CC261", 3, 1, 1, 1, "平原"));
            tt.put("forest", new TerrainType("森林", "#228B22", 2, 1, 2, 2, "森林"));
            tt.put("mountain", new TerrainType("山地", "#808080", 0, 2, 5, 3, "山地"));
            tt.put("desert", new TerrainType("沙漠", "#DDC88D", 1, 2, 1, 2, "沙漠"));
            tt.put("swamp", new TerrainType("沼泽", "#556B2F", 2, 0, 1, 2, "沼泽"));
            tt.put("tundra", new TerrainType("冻土", "#A8C4D8", 1, 1, 1, 2, "冻土"));
            tt.put("hills", new TerrainType("丘陵", "#BDB76B", 2, 1, 3, 2, "丘陵"));
            return tt;
        }
    }

    // ── Province ───────────────────────────────────────────

    /**
     * A province — a named region with a color and list of hexes.
     *
     * @param hexes       hex keys belonging to this province
     * @param color       display color
     * @param tag         short identifier tag
     * @param description optional description
     */
    @JsonDeserialize
    public record Province(
            @JsonProperty("hexes") List<String> hexes,
            @JsonProperty("color") String color,
            @JsonProperty("tag") String tag,
            @JsonProperty("description") String description) {
        public Province {
            if (hexes == null) hexes = List.of();
            if (color == null) color = "#ff0000";
            if (tag == null) tag = "";
            if (description == null) description = "";
            // Defensive copy + freeze (SpotBugs EI_EXPOSE_REP)
            hexes = List.copyOf(hexes);
        }
    }

    // ── City ───────────────────────────────────────────────

    /**
     * A city located on a specific hex.
     *
     * @param q           hex column coordinate
     * @param r           hex row coordinate
     * @param name        city name
     * @param description optional description
     */
    @JsonDeserialize
    public record City(
            @JsonProperty("q") int q,
            @JsonProperty("r") int r,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description) {
        public City {
            if (name == null) name = "";
            if (description == null) description = "";
        }
    }

    // ── River (deprecated) ──────────────────────────────────

    /**
     * A deprecated river definition.
     *
     * @param name  river name
     * @param path  ordered hex keys along the river
     * @param width river width in pixels
     * @param color display color
     */
    @JsonDeserialize
    @Deprecated
    public record River(
            @JsonProperty("name") String name,
            @JsonProperty("path") List<String> path,
            @JsonProperty("width") int width,
            @JsonProperty("color") String color) {
        public River {
            if (name == null) name = "";
            if (path == null) path = List.of();
            if (width < 1) width = 3;
            if (color == null) color = "#3295D2";
            // Defensive copy + freeze (SpotBugs EI_EXPOSE_REP)
            path = List.copyOf(path);
        }
    }

    /**
     * A deprecated road definition.
     *
     * @param name  road name
     * @param path  ordered hex keys along the road
     * @param width road width in pixels
     * @param color display color
     */
    @JsonDeserialize
    @Deprecated
    public record Road(
            @JsonProperty("name") String name,
            @JsonProperty("path") List<String> path,
            @JsonProperty("width") int width,
            @JsonProperty("color") String color) {
        public Road {
            if (name == null) name = "";
            if (path == null) path = List.of();
            if (width < 1) width = 2;
            if (color == null) color = "#f5deb3";
            // Defensive copy + freeze (SpotBugs EI_EXPOSE_REP)
            path = List.copyOf(path);
        }
    }

    // ── PathwayGroup ────────────────────────────────────────

    /**
     * A pathway group definition (e.g., river, road).
     *
     * @param id          unique identifier
     * @param name        display name
     * @param color       display color
     * @param description optional description
     * @param visible     whether the pathway is visible by default
     */
    @JsonDeserialize
    public record PathwayGroup(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("color") String color,
            @JsonProperty("description") String description,
            @JsonProperty("visible") boolean visible) {
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
     *
     * @param id         unique identifier
     * @param terrain    the terrain type identifier
     * @param color      fill color
     * @param boundary   deprecated single outer ring boundary
     * @param boundaries list of boundary rings (outer + holes)
     * @param isWater    whether this is a water region
     * @param hexKeys    hex keys belonging to this region
     */
    @JsonDeserialize
    public record CompressedRegion(
            @JsonProperty("id") String id,
            @JsonProperty("terrain") String terrain,
            @JsonProperty("color") String color,
            @JsonProperty("boundary") @Deprecated List<Pt> boundary,
            @JsonProperty("boundaries") List<List<Pt>> boundaries,
            @JsonProperty("isWater") boolean isWater,
            @JsonProperty("hexKeys") Set<String> hexKeys) {
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
            // Deep freeze inner lists and outer list (SpotBugs EI_EXPOSE_REP)
            boundary = List.copyOf(boundary);
            List<List<Pt>> frozenBoundaries = new ArrayList<>();
            for (var ring : boundaries) {
                frozenBoundaries.add(List.copyOf(ring));
            }
            boundaries = Collections.unmodifiableList(frozenBoundaries);
            hexKeys = Set.copyOf(hexKeys);
        }
        /** Derived from hexKeys size; not serialized. */
        public int size() {
            return hexKeys.size();
        }
    }
}

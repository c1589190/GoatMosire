package com.goatmosire.map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A partial map diff — only the changes relative to a parent node.
 * Stored as worlds/{id}/nodes/{nid}_map.json for non-root nodes.
 *
 * <p>A missing _map.json means "no changes from parent".
 * The root node (n0000) always stores a full MapData, not a MapDiff.
 *
 * @param parentNodeId      parent node identifier
 * @param changed           hex cells that changed (keyed by "q_r")
 * @param removed           hex keys that were removed
 * @param provincesChanged  province definitions that changed
 * @param provincesRemoved  province ids that were removed
 * @param citiesAdded       city definitions that were added
 * @param citiesRemoved     city ids that were removed
 * @param riversAdded       river definitions that were added
 * @param roadsAdded        road definitions that were added
 * @param compressedRegions compressed region data
 */
@JsonDeserialize
public record MapDiff(
        @JsonProperty("parentNodeId") String parentNodeId,
        @JsonProperty("changed") Map<String, MapData.HexCell> changed,
        @JsonProperty("removed") List<String> removed,
        @JsonProperty("provinces_changed") Map<String, MapData.Province> provincesChanged,
        @JsonProperty("provinces_removed") List<String> provincesRemoved,
        @JsonProperty("cities_added") Map<String, MapData.City> citiesAdded,
        @JsonProperty("cities_removed") List<String> citiesRemoved,
        @JsonProperty("rivers_added") List<MapData.River> riversAdded,
        @JsonProperty("roads_added") List<MapData.Road> roadsAdded,
        @JsonProperty("compressedRegions") List<MapData.CompressedRegion> compressedRegions) {
    public MapDiff {
        if (parentNodeId == null || parentNodeId.isBlank()) throw new IllegalArgumentException("parentNodeId required");
        if (changed == null) changed = Map.of();
        if (removed == null) removed = List.of();
        if (provincesChanged == null) provincesChanged = Map.of();
        if (provincesRemoved == null) provincesRemoved = List.of();
        if (citiesAdded == null) citiesAdded = Map.of();
        if (citiesRemoved == null) citiesRemoved = List.of();
        if (riversAdded == null) riversAdded = List.of();
        if (roadsAdded == null) roadsAdded = List.of();
        if (compressedRegions == null) compressedRegions = List.of();
        // Defensive copy + freeze (SpotBugs EI_EXPOSE_REP)
        changed = Map.copyOf(changed);
        removed = List.copyOf(removed);
        provincesChanged = Map.copyOf(provincesChanged);
        provincesRemoved = List.copyOf(provincesRemoved);
        citiesAdded = Map.copyOf(citiesAdded);
        citiesRemoved = List.copyOf(citiesRemoved);
        riversAdded = List.copyOf(riversAdded);
        roadsAdded = List.copyOf(roadsAdded);
        compressedRegions = List.copyOf(compressedRegions);
    }

    /** Is this diff empty (no changes)? */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() {
        return changed.isEmpty()
                && removed.isEmpty()
                && provincesChanged.isEmpty()
                && provincesRemoved.isEmpty()
                && citiesAdded.isEmpty()
                && citiesRemoved.isEmpty()
                && riversAdded.isEmpty()
                && roadsAdded.isEmpty()
                && compressedRegions.isEmpty();
    }

    /**
     * Compute diff between two full MapData instances.
     * Only tracks hex/province/city changes; rivers and roads are not diffed
     * (they use pixel coordinates which change with zoom/pan — store full list instead).
     *
     * @param parentNodeId the parent node id
     * @param parent       the parent (base) map data
     * @param child        the child (new) map data
     * @return a new MapDiff capturing the differences
     */
    public static MapDiff compute(String parentNodeId, MapData parent, MapData child) {
        Map<String, MapData.HexCell> changed = new LinkedHashMap<>();
        List<String> removed = new ArrayList<>();

        // Hex changes
        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(parent.hexes().keySet());
        allKeys.addAll(child.hexes().keySet());
        for (String key : allKeys) {
            MapData.HexCell pc = parent.hexes().get(key);
            MapData.HexCell cc = child.hexes().get(key);
            if (cc == null) {
                removed.add(key);
            } else if (pc == null || !pc.equals(cc)) {
                changed.put(key, cc);
            }
        }

        // Province changes
        Map<String, MapData.Province> provChanged = new LinkedHashMap<>();
        List<String> provRemoved = new ArrayList<>();
        Set<String> allProvs = new LinkedHashSet<>();
        allProvs.addAll(parent.provinces().keySet());
        allProvs.addAll(child.provinces().keySet());
        for (String p : allProvs) {
            MapData.Province pp = parent.provinces().get(p);
            MapData.Province cp = child.provinces().get(p);
            if (cp == null) provRemoved.add(p);
            else if (pp == null || !pp.equals(cp)) provChanged.put(p, cp);
        }

        // City changes
        Map<String, MapData.City> citiesAdded = new LinkedHashMap<>();
        List<String> citiesRemoved = new ArrayList<>();
        for (var e : child.cities().entrySet()) {
            if (!parent.cities().containsKey(e.getKey())) citiesAdded.put(e.getKey(), e.getValue());
        }
        for (var e : parent.cities().entrySet()) {
            if (!child.cities().containsKey(e.getKey())) citiesRemoved.add(e.getKey());
        }

        return new MapDiff(
                parentNodeId,
                changed,
                removed,
                provChanged,
                provRemoved,
                citiesAdded,
                citiesRemoved,
                child.rivers(),
                child.roads(),
                child.compressedRegions());
    }
}

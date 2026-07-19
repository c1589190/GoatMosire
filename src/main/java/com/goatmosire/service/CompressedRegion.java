package com.goatmosire.service;

import com.goatmosire.map.MapData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A compressed representation of a large contiguous same-terrain region.
 * Stored separately from individual hexes; rendered as a filled polygon.
 */
public class CompressedRegion {
    private String id;
    private String terrain;
    private String color;
    private List<MapData.Pt> boundary; // RDP-simplified polygon for rendering
    private int size; // number of hexes in this region
    private boolean isWater;

    // Hex keys in this region (serialized for frontend rendering)
    private Set<String> hexKeys;

    /** Default constructor for serialization. */
    public CompressedRegion() {}

    /**
     * Constructs a compressed region with the given properties.
     * @param id unique region identifier
     * @param terrain terrain type
     * @param color display color
     * @param hexKeys set of hex keys in this region
     * @param boundary polygon boundary (RDP-simplified)
     * @param isWater whether this region is water terrain
     */
    public CompressedRegion(
            String id, String terrain, String color, Set<String> hexKeys, List<MapData.Pt> boundary, boolean isWater) {
        this.id = id;
        this.terrain = terrain;
        this.color = color;
        this.hexKeys = hexKeys == null ? null : new HashSet<>(hexKeys);
        this.boundary = boundary == null ? null : new ArrayList<>(boundary);
        this.size = hexKeys != null ? hexKeys.size() : 0;
        this.isWater = isWater;
    }

    // Getters for Jackson serialization
    public String getId() {
        return id;
    }

    public String getTerrain() {
        return terrain;
    }

    public String getColor() {
        return color;
    }

    public List<MapData.Pt> getBoundary() {
        return boundary == null ? null : Collections.unmodifiableList(boundary);
    }

    public int getSize() {
        return size;
    }

    public boolean getIsWater() {
        return isWater;
    }

    public Set<String> getHexKeys() {
        return hexKeys == null ? null : Collections.unmodifiableSet(hexKeys);
    }

    @Override
    public String toString() {
        return "CR[" + terrain + " x" + size + (isWater ? " water" : "") + "]";
    }
}

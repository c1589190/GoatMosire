package com.goatmosire.service;

import com.gsim.map.MapData;
import java.util.*;

/**
 * A compressed representation of a large contiguous same-terrain region.
 * Stored separately from individual hexes; rendered as a filled polygon.
 */
public class CompressedRegion {
    public String id;
    public String terrain;
    public String color;
    public List<MapData.Pt> boundary;  // RDP-simplified polygon for rendering
    public int size;                    // number of hexes in this region
    public boolean isWater;

    // Hex keys in this region (serialized for frontend rendering)
    public Set<String> hexKeys;

    public CompressedRegion() {}

    public CompressedRegion(String id, String terrain, String color,
                            Set<String> hexKeys, List<MapData.Pt> boundary, boolean isWater) {
        this.id = id;
        this.terrain = terrain;
        this.color = color;
        this.hexKeys = hexKeys;
        this.boundary = boundary;
        this.size = hexKeys.size();
        this.isWater = isWater;
    }

    // Getters for Jackson serialization
    public String getId() { return id; }
    public String getTerrain() { return terrain; }
    public String getColor() { return color; }
    public List<MapData.Pt> getBoundary() { return boundary; }
    public int getSize() { return size; }
    public boolean getIsWater() { return isWater; }
    public Set<String> getHexKeys() { return hexKeys; }

    @Override
    public String toString() {
        return "CR[" + terrain + " x" + size + (isWater ? " water" : "") + "]";
    }
}

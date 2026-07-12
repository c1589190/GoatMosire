package com.goatmosire;

import com.gsim.map.*;
import java.nio.file.Path;
import java.util.*;

public class DebugPath {
    public static void main(String[] args) {
        Path wd = Path.of(System.getProperty("user.home"), "GSimulator/worlds");
        MapData map = MapResolver.resolve(wd, "logdemo", "n0000");
        System.out.println("Hexes: " + map.hexes().size());
        
        // Simple BFS from (-5,0)
        String start = "-5_0";
        int[][] dirs = {{1,0},{1,-1},{0,-1},{-1,0},{-1,1},{0,1}};
        var q = new ArrayDeque<String>();
        var cameFrom = new HashMap<String, String>();
        var visited = new HashSet<String>();
        q.add(start);
        visited.add(start);
        String found = null;
        
        bfs: while (!q.isEmpty()) {
            String cur = q.poll();
            MapData.HexCell cell = map.hexes().get(cur);
            if (cell != null && "water".equals(cell.terrain())) { found = cur; break; }
            int[] coords = MapData.parseHexKey(cur);
            for (int[] d : dirs) {
                String nk = MapData.hexKey(coords[0]+d[0], coords[1]+d[1]);
                if (map.hexes().containsKey(nk) && visited.add(nk)) {
                    cameFrom.put(nk, cur);
                    q.add(nk);
                }
            }
        }
        
        System.out.println("Found: " + found);
        // reconstruct
        var path = new ArrayList<String>();
        String k = found;
        while (k != null) { path.add(k); k = cameFrom.get(k); }
        Collections.reverse(path);
        System.out.println("Path: " + path);
    }
}

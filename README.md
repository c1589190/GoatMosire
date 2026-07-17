# GoatMosire

> Hex map editor and MCP bridge for GSimulator — a turn-based grand strategy engine.  
> Built for both human GMs and AI agents (Claude Code, Codex, Cline, etc.).

[![Java](https://img.shields.io/badge/Java-21-blue)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Maven-3.9+-C71A36)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-green)](./LICENSE)

GoatMosire provides a **hexagonal map editor** with an HTML5 Canvas frontend, a **REST API** for programmatic access, and an **MCP stdio server** exposing 22 tools for AI agents to query and modify the game world.

---

## Quick Start

### Prerequisites

- **Java 21+**
- **Maven 3.9+**
- [GSimulator](https://github.com/your-org/GSimulator) sibling repo with `gsim-core` built

### Build & Run

```bash
# 1. Install gsim-core to local Maven repo (from sibling GSimulator project)
cd ../GSimulator && mvn install -N && mvn install -pl gsim-core -DskipTests && cd -

# 2. Build GoatMosire (shaded JAR)
cd GoatMosire && mvn package -DskipTests -q

# 3. Start (HTTP + MCP, worldsDir defaults to ./worlds)
java -Dgoatmosire.worldsDir=./worlds -jar target/goatmosire-0.1.0-SNAPSHOT.jar

# 4. Open the map editor
open http://localhost:8711
```

### Run Modes

| Mode | Command | Behavior |
|------|---------|----------|
| Full | `java -jar goatmosire.jar` | HTTP :8711 + MCP stdio |
| HTTP-only | `java -jar goatmosire.jar --http-only` | HTTP :8711 only |
| MCP-only | `java -jar goatmosire.jar --mcp-only` | MCP stdio only |

---

## Architecture

```
 ┌──────────────────────────────────────────────────┐
 │                  Browser                          │
 │  index.html (single-file SPA)                    │
 │  Canvas renderer · Terrain/paint tools            │
 │  Province editor · River editor · Tag system     │
 └────────────────────┬─────────────────────────────┘
                      │ HTTP :8711
 ┌────────────────────┴─────────────────────────────┐
 │  GoatMosire                          Java 21      │
 │  ┌──────────────────────────────────────────────┐ │
 │  │  HTTP Server (jdk.httpserver)                │ │
 │  │  MapApiHandler · StaticFileHandler           │ │
 │  └──────────────────┬───────────────────────────┘ │
 │  ┌──────────────────┴───────────────────────────┐ │
 │  │  MapService                                  │ │
 │  │  resolve · saveFull · saveDiff · A* pathfind │ │
 │  │  TerrainCanvas · ContourQueryEngine · Cache  │ │
 │  └──────────────────┬───────────────────────────┘ │
 │  ┌──────────────────┴───────────────────────────┐ │
 │  │  McpToolRegistry (22 MCP tools)              │ │
 │  │  stdio JSON-RPC 2.0 for AI agents            │ │
 │  └──────────────────┬───────────────────────────┘ │
 └─────────────────────┼─────────────────────────────┘
                       │
 ┌─────────────────────┼─────────────────────────────┐
 │  GSimulator         │                             │
 │  gsim-core          │                             │
 │  MapData · MapDiff · MapStore · MapResolver       │
 │  worlds/{id}/nodes/{nid}_map.json                │
 └─────────────────────┴─────────────────────────────┘
```

### Key Design Choices

- **No Spring** — pure `jdk.httpserver` for HTTP, vanilla JS for frontend
- **Diff inheritance** — root node stores full map; child nodes store only changed hexes
- **TerrainBlock system** — ordered polygon-based terrain with overlap/hollow/merge rules
- **LRU cache** — 32-entry `LinkedHashMap` per world/node key; evicts entire world prefix on mutation
- **Zero external HTTP dependencies** — just `gsim-core`, Jackson, SLF4J/Log4j2

---

## HTTP API

All endpoints are under `/api/map`. Responses are JSON. CORS enabled.

### Read endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/map` | List all worlds with map data |
| `GET` | `/api/map/{worldId}` | Get full map (optional `?node={nid}`) |
| `GET` | `/api/map/{worldId}/nodes` | List all nodes with turn info |
| `GET` | `/api/map/{worldId}/history` | Get map history chain |
| `GET` | `/api/map/{worldId}/latest-texts` | Recent checkpoint narrative texts |
| `GET` | `/api/map/{worldId}/version` | Map file modification timestamp |
| `GET` | `/api/map/{worldId}/contour` | Terrain contour data |
| `GET` | `/api/map/{worldId}/blocks` | Terrain block list |
| `GET` | `/api/map/{worldId}/river-path` | A* river pathfinding |
| `POST` | `/api/map/{worldId}/expand` | Expand map boundary (`?direction=E&radius=N`) |
| `POST` | `/api/map/{worldId}/compress` | Compress regions (`?minSize=100`) |
| `POST` | `/api/map/{worldId}/decompress` | Decompress a region (`?region=xxx`) |
| `POST` | `/api/map/{worldId}/decompress-at` | Decompress at coordinates |

### Example

```bash
# List worlds
curl http://localhost:8711/api/map

# Get sample hex from logdemo world
curl "http://localhost:8711/api/map/logdemo?node=n0000" | jq '.hexes["10_-5"]'
```

---

## MCP Tools (22 tools, prefix: `goatmosire_`)

The MCP server implements JSON-RPC 2.0 over stdio (line-delimited). All tools auto-save after mutation.

### Query Tools

| Tool | Description | Required Params |
|------|-------------|-----------------|
| `get_hex` | Query a single hex cell — terrain, resources, province | `worldId`, `q`, `r` |
| `get_neighbors` | Get 6 neighboring hexes of a coordinate | `worldId`, `q`, `r` |
| `query_radius` | All hexes within N steps of center | `worldId`, `q`, `r`, `radius` |
| `get_province` | Full province info — hexes, center, adjacency, terrain composition | `worldId`, `name` |
| `list_regions` | All regions with centers, terrain, adjacency relationships | `worldId` |
| `get_cities` | List all cities with coordinates | `worldId` |
| `get_distance` | Hex distance — by coordinates or region names | `worldId` + coords/names |
| `get_diff` | Map changes for a specific node | `worldId`, `nodeId` |
| `get_history` | Full history chain across nodes | `worldId` |
| `find_river_path` | Minimum-cost path to water (Dijkstra, terrain moveCost) | `worldId`, `q`, `r` |

### Mutation Tools

| Tool | Description |
|------|-------------|
| `create_region` | Create a new empty region |
| `delete_region` | Delete a region by name |
| `rename_region` | Rename across all data stores (MapData + all checkpoints) |
| `update_region` | Change tag, description, color, or hex list |
| `add_hex_to_region` | Add a single hex to a region |
| `remove_hex_from_region` | Remove a hex from a region |
| `update_terrain_type` | Change terrain definition (yield, color, moveCost) |

### Checkpoint (Document) Tools

| Tool | Description | Required Params |
|------|-------------|-----------------|
| `list_checkpoints` | List all checkpoints with element counts | `worldId` |
| `get_checkpoint` | Read elements, filter by key or tags | `worldId`, `checkpoint` |
| `add_checkpoint_element` | Create new document entry | `worldId`, `checkpoint`, `key`, `value` |
| `update_checkpoint_element` | Modify existing document | `worldId`, `checkpoint`, `key` |
| `delete_checkpoint_element` | Remove a document entry | `worldId`, `checkpoint`, `key` |

### Init Tool

| Tool | Description |
|------|-------------|
| `init_nation` | One-shot: flood-fill territory + faction description + narrative + capital city |

### Configuring in Agent Software

Add to your MCP config (e.g. `.claude/mcp.json`, `.codex/mcp.json`):

```json
{
  "mcpServers": {
    "goatmosire": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-Dgoatmosire.worldsDir=./worlds",
        "-jar", "/path/to/GoatMosire/target/goatmosire-0.1.0-SNAPSHOT.jar",
        "--mcp-only"
      ]
    }
  }
}
```

---

## Web Editor

The built-in editor (`http://localhost:8711`) is a single-file SPA using vanilla JS + HTML5 Canvas.

**Frontend modules** (`src/main/resources/web/`):

| File | Purpose |
|------|---------|
| `index.html` | Main SPA with Canvas rendering |
| `map-api.js` | Fetch wrapper for REST API |
| `js/render.js` | Hex grid, terrain, province, river rendering |
| `js/state.js` | Central state management |
| `js/terrain.js` | Terrain painting tools |
| `js/province.js` | Province/region editing |
| `js/river.js` | River drawing with edge masks |
| `js/paint.js` | Brush-based painting tools |
| `js/events.js` | Mouse/keyboard event handling |
| `js/hex-math.js` | Hex coordinate math utilities |
| `js/tags.js` | Tag system UI |
| `js/ui.js` | Toolbar and panel management |
| `js/generator.js` | Map generation dialog |
| `js/expand.js` | Map expansion controls |
| `css/editor.css` | Editor styling |

---

## Terrain System

Default terrain types (customizable via `update_terrain_type`):

| Terrain | Color | Food | Gold | Stone | Move Cost |
|---------|-------|------|------|-------|-----------|
| `lowland` | `#5B8C3E` | 3 | 1 | 1 | 1 |
| `plains` | `#C4A84B` | 2 | 2 | 1 | 1 |
| `hills` | `#8B7355` | 1 | 1 | 3 | 2 |
| `mountain` | `#9B9B9B` | 0 | 0 | 4 | 4 |
| `water` | `#3295D2` | 1 | 0 | 0 | 3 |
| `desert` | `#D4C5A0` | 0 | 1 | 0 | 2 |
| `forest` | `#2D6A3F` | 2 | 0 | 2 | 2 |
| `tundra` | `#B0C4D8` | 1 | 0 | 0 | 2 |
| `swamp` | `#5C6B4A` | 1 | 0 | 0 | 3 |

---

## Coordinate System

**Axial coordinates** (q, r) with pointy-top hexagons.  
Hex distance: `(|q1-q2| + |r1-r2| + |(-q1-r1) - (-q2-r2)|) / 2`

```
        /\
       /  \
      |    |
       \  /
        \/

Neighbor directions (q,r):
  E (1,0)  SE (1,-1)  SW (0,-1)
  W (-1,0) NW (-1,1)  NE (0,1)
```

---

## World Data Structure

```
worlds/{worldId}/
├── world.json              # World metadata (id, name, createdAt)
├── active.json             # Current active node
└── nodes/
    ├── n0000.json          # Node data (turn, worldTime, checkpoints)
    ├── n0000_map.json      # Full map (root) or diff (child nodes)
    ├── n0001.json
    ├── n0001_map.json
    └── ...
```

- **Root node (n0000)** stores the full map
- **Child nodes** store diffs relative to their parent (only changed + removed hexes)
- Checkpoint data (narrative, factions, worldview, characters, map info) lives inside node JSON files

---

## Configuration

All settings via system properties or environment variables:

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `goatmosire.worldsDir` | `GOATMOSIRE_WORLDS_DIR` | `./worlds` | GSim worlds directory |
| `goatmosire.port` | `GOATMOSIRE_PORT` | `8711` | HTTP listen port |
| `goatmosire.httpOnly` | — | `false` | Disable MCP, HTTP only |
| `goatmosire.mcpOnly` | — | `false` | Disable HTTP, MCP only |

---

## Package Layout

| Package | Role |
|---------|------|
| `com.goatmosire` | Entry point (`GoatMosireApp`) — CLI parsing, server wiring |
| `com.goatmosire.config` | `GoatMosireConfig` record |
| `com.goatmosire.http` | HTTP server (`GoatmosireHttpServer`, `MapApiHandler`, `StaticFileHandler`) |
| `com.goatmosire.service` | Core logic — `MapService`, `TerrainCanvas`, `TerrainGeometry`, `ContourQueryEngine`, `MapGenerator` |
| `com.goatmosire.mcp` | MCP server (`McpServer`, `McpToolRegistry` — 22 tools) |

21 Java source files, 1 test file.

---

## Dependencies

- **gsim-core** `0.1.0-Alpha1` — map data types, storage, resolution (bundled in `lib/`)
- **Jackson** `2.18.2` — JSON
- **SLF4J + Log4j2** `2.0.16` / `2.26.0` — logging
- **JUnit 5** `5.11.4` — testing

No Spring, no external HTTP libraries. The shaded JAR is ~5MB.

---

## Sample Worlds

Two sample worlds ship in the `worlds/` directory:

| World | Size | Regions | Notes |
|-------|------|---------|-------|
| **testgen** | 30×30 hexes (120,601 cells) | 1 province | Auto-generated map with 9 terrain types |
| **logdemo** | ~100×100 hexes | 96 regions | Full strategy game world with named nations, factions, and checkpoint narratives |

---

## Related Documentation

- [MCP_GUIDE.md](./MCP_GUIDE.md) — Detailed MCP usage with examples
- [docs/TECHNICAL.md](./docs/TECHNICAL.md) — Architectural deep-dive
- [CLAUDE.md](./CLAUDE.md) — AI agent working instructions

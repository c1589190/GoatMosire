# GoatMosire Architecture

## Overview

GoatMosire is a hex-grid map editor and MCP (Model Context Protocol) bridge for GSimulator, a multi-agent turn-based grand strategy engine. It serves dual audiences:

1. **Human GMs** — browser-based Canvas editor for terrain painting, province boundaries, river/road pathways
2. **AI Agents** — MCP stdio interface exposing 24 map-editing tools + all GSim Agent/WorldInfo tools

### Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 21 |
| Build | Maven 3, maven-shade-plugin (single fat JAR) |
| HTTP | `com.sun.net.httpserver` (JDK built-in, no Spring) |
| MCP | Raw JSON-RPC 2.0 over stdio (no library dependency) |
| Frontend | Vanilla JS + HTML5 Canvas (no framework, no build step) |
| Serialization | Jackson (ObjectMapper) |
| Logging | SLF4J + Log4j2 |

---

## Package Map

```
com.goatmosire
├── GoatMosireApp.java              ← Entry point, wires everything
├── config/GoatMosireConfig.java    ← Configuration record
├── http/
│   ├── GoatmosireHttpServer.java   ← HTTP server (jdk.httpserver)
│   ├── MapApiHandler.java          ← REST /api/map/* endpoints
│   └── StaticFileHandler.java      ← Classpath static file servlet
├── map/
│   ├── MapData.java                ← Core data model (records)
│   ├── MapDiff.java                ← Node diff record
│   ├── MapResolver.java            ← Parent-chain resolution
│   ├── MapStore.java               ← JSON file persistence
│   └── MapStoreException.java      ← Checked persistence error
├── mcp/
│   ├── McpServer.java              ← MCP stdio server
│   └── McpToolRegistry.java        ← 24 goatmosire_* tools
└── service/
    ├── MapService.java             ← Core coordinator
    ├── TerrainCanvas.java          ← Block-based terrain editor
    ├── TerrainBlockProcessor.java  ← @Deprecated terrain block queries
    ├── TerrainGeometry.java        ← Hex math & geometry
    ├── LassoProcessor.java         ← Freehand selection + flood fill
    ├── MapGenerator.java           ← Procedural continent generator
    ├── ContinentContour.java       ← Compact contour representation
    ├── ContourLayer.java           ← Editor-drawn terrain layer
    ├── ContourQueryEngine.java     ← Point-in-contour queries
    ├── CompressionService.java     ← Region compression for rendering
    ├── CompressionValidator.java   ← CR validation & auto-repair
    ├── CompressedRegion.java       ← Compressed region model
    ├── CheckpointService.java      ← GSim checkpoint CRUD
    ├── NodeSyncService.java        ← Map ↔ GSim node sync
    └── SimplexNoise.java           ← Simplex noise generator
```

---

## Data Model (com.goatmosire.map)

### MapData

Central data structure — a single Java record holding the entire map state:

```
MapData
├── hexes: Map<String, HexCell>         ← "q_r" → cell
├── terrainBlocks: List<TerrainBlock>   ← Ordered polygon layers
├── provinces: Map<String, Province>    ← name → province
├── cities: Map<String, City>           ← name → city
├── rivers: List<River>                 ← @Deprecated, see pathwayGroups
├── roads: List<Road>                   ← @Deprecated, see pathwayGroups
├── terrainTypes: Map<String, TerrainType>  ← terrain configs
├── pathwayGroups: Map<String, PathwayGroup>  ← Modern river/road system
└── compressedRegions: List<CompressedRegion> ← Rendering optimization
```

**Record compact constructors** defensively freeze all mutable collection fields using `List.copyOf()`, `Set.copyOf()`, and `Map.copyOf()` to prevent internal representation exposure.

#### HexCell
- `color`, `terrain`, `symbol`, `symbolColor`, `description` — display properties
- `riverMask` — 6-bit edge mask (E=0, SE=1, SW=2, W=3, NW=4, NE=5)
- `edgeTags` — Per-edge string tags (cliffs, roads, etc.), deep-frozen in constructor

#### TerrainBlock
- `terrain`: type string (e.g. "forest", "mountain", "water")
- `boundary`: polygon as `List<Pt>` where Pt has `x()`, `y()` accessors
- `hexKeys`: `Set<String>` of contained hex coordinates

### MapDiff

Records the delta between a parent node and child node. Used for efficient storage — child nodes only store changed hexes. Fields: `changed` (Map), `removed` (List), `provincesChanged`, `provincesRemoved`, `citiesAdded`, `citiesRemoved`, `riversAdded`/`roadsAdded` (@Deprecated), `compressedRegions`.

### MapResolver

Walks the parent chain from a node back to n0000, applying diffs along the way to reconstruct the full map state. Static utility — no instance state.

### MapStore

JSON file persistence layer. Operations: `saveFull`, `saveDiff`, `loadFull`, `loadDiff`. Uses Jackson `ObjectMapper` for (de)serialization. Throws `MapStoreException` (not bare `RuntimeException`).

---

## Request Flow

### HTTP (Human GM)

```
Browser Canvas → HTTP PUT/GET → MapApiHandler → MapService
                                                   ├── MapResolver (resolve)
                                                   ├── MapStore (persist)
                                                   ├── TerrainCanvas (terrain blocks)
                                                   ├── LassoProcessor (freehand fill)
                                                   ├── CompressionService (region optimization)
                                                   └── CompressionValidator (auto-repair)
```

### MCP (AI Agent)

```
AI Agent → MCP stdio → McpServer
                          ├── goatmosire_* → McpToolRegistry → MapService
                          └── gsim_* → GsimMcpToolRegistry (embedded GSim API)
```

### Embedded GSimulator

GoatMosire embeds a full GSimulator HTTP API on port 8710 (configurable) in a background thread. This is the bridge between map editing and agent/world management. MCP tools prefixed `gsim_*` are routed to the embedded GSim API via `GsimMcpToolRegistry`, enabling AI agents to manage worlds, agents, LLM configs, and WorldInfo elements without a separate GSimulator process.

---

## Terrain System

### Block-based terrain (TerrainCanvas)

Terrain is stored as an ordered list of `TerrainBlock` polygons. The last block that covers a hex determines its terrain. This is more efficient than per-hex terrain storage and enables natural brush-like editing.

**Overlap rules:**
1. Same terrain → polygons merge
2. Different terrain, one fully inside the other → inner hollows outer
3. Different terrain, overlap but not fully contained → larger block retains priority (max-retention)

### Compression (CompressionService)

For large worlds (10K+ hexes), storing every hex individually is wasteful. `CompressionService` uses BFS to find contiguous same-terrain regions and creates `CompressedRegion` entries. These are stored alongside full hex data — hexes are always authoritative; compressedRegions are a rendering optimization that can be regenerated any time.

`CompressionValidator` runs transparently on every `MapService.resolve()` to validate and auto-repair CR boundaries.

### Legacy system (@Deprecated)

- `TerrainBlockProcessor` — replaced by `TerrainCanvas` block system
- `MapData.River` / `MapData.Road` — replaced by `MapData.PathwayGroup` (multi-group pathway editing)
- `TerrainGeometry.hexSetToBoundary()` — use newer boundary methods with hole support

---

## MCP Tool Registry

24 tools in `McpToolRegistry`, organized into 5 registration groups:

| Group | Tools | Purpose |
|-------|-------|---------|
| `registerQueryTools()` | get_hex, get_province, get_neighbors, query_radius, get_cities, find_river_path, list_regions | Read operations |
| `registerDiffTools()` | get_diff, get_history, get_distance | Version/history |
| `registerRegionTools()` | update_region, add_hex_to_region, remove_hex_from_region, create_region, delete_region, rename_region | Region CRUD |
| `registerCheckpointTools()` | list_checkpoints, get_checkpoint, add_checkpoint_element, update_checkpoint_element, delete_checkpoint_element | GSim checkpoint sync |
| `registerInitTools()` | init_nation, update_terrain_type | Map initialization |

---

## Caching Strategy

### MapService LRU Cache

- **Implementation**: Static `LruCache` inner class extending `LinkedHashMap` with `removeEldestEntry` override
- **Capacity**: 32 entries
- **Key**: `"worldId/nodeId"`
- **Invalidation**: Entire world prefix evicted on any mutation (saveFull, saveDiff)
- **Thread safety**: `Collections.synchronizedMap()` wrapper

### TerrainCanvas Cache

- **Implementation**: `ConcurrentHashMap<String, TerrainCanvas>` keyed by `worldId`
- **Lazy init**: Canvas created on first access, loaded from stored terrain blocks
- **Lifecycle**: Same lifetime as MapService instance

### ContourQueryEngine Cache

- **Implementation**: Static `LruCache` inner class, identical pattern to MapService
- **Purpose**: Avoids re-materializing contour hex data on repeated queries

---

## Configuration System

### GoatMosireConfig

A Java record loaded from system properties with environment variable fallbacks:

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `goatmosire.worldsDir` | `GOATMOSIRE_WORLDS_DIR` | `./worlds` | GSim worlds directory |
| `goatmosire.importDir` | — | `./import` | GSim import/docs directory |
| `goatmosire.port` | `GOATMOSIRE_PORT` | `8711` | HTTP server port |
| `goatmosire.gsimPort` | `GOATMOSIRE_GSIM_PORT` | `8710` | Embedded GSim API port |
| `goatmosire.httpOnly` | — | `false` | Disable MCP |
| `goatmosire.mcpOnly` | — | `false` | Disable HTTP |
| `goatmosire.noGsim` | — | `false` | Disable embedded GSim |

---

## Code Quality

### Three-plugin pipeline (verify phase)

```
Spotless (format) → Checkstyle (style) → SpotBugs (bugs)
      ↓                   ↓                    ↓
  Palantir Java     gsim_checks.xml      Max effort
  Format 2.50.0     10.26.1              Low threshold
```

- **0 Checkstyle warnings** enforced
- **0 SpotBugs High** issues (target: 0 Medium)
- **16 JUnit 5 tests** (TerrainCanvasTest) pass

### Conventions

- All public methods have Javadoc with `@param`/`@return`/`@throws` tags
- Record components documented in record-level Javadoc
- Star imports forbidden — explicit imports only
- Utility classes have private constructors
- `@SuppressWarnings("EI_EXPOSE_REP2")` on shared service constructor parameters

---

## Extension Points

### Adding a new MCP tool

1. Add tool definition in `McpToolRegistry.registerAll()` (choose the right sub-method)
2. Implement handler method (`handleXxx(JsonNode args)`)
3. Add the `case` branch in `execute()`

### Adding a new HTTP endpoint

1. Add path matching in `MapApiHandler.handle()`
2. Implement handler method
3. Frontend: add fetch call in `map-api.js`

### Adding a new terrain type

1. Add entry in `MapData.TerrainType.defaults()`
2. Add color mapping in `CompressionService.terrainColor()` switch
3. No frontend changes needed — palette auto-detects from terrain types

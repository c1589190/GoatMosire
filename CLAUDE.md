# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Prerequisite: install gsim-lib to local Maven repo (from example/GSimulator)
cd example/GSimulator && mvn install -pl gsim-lib -DskipTests && cd -

# Build (shaded JAR, main class: com.goatmosire.GoatMosireApp)
mvn package -DskipTests -q

# Run tests (JUnit 5)
mvn test

# Full code quality check (runs Spotless + Checkstyle + SpotBugs in verify phase)
mvn verify

# Auto-format code (Spotless with Palantir Java Format)
mvn spotless:apply

# Start — HTTP :8711 + MCP stdio (default):
java -Dgoatmosire.worldsDir=./worlds -jar target/goatmosire-0.1.0-SNAPSHOT.jar

# Start HTTP-only (worldsDir defaults to ./worlds if not specified):
java -jar target/goatmosire-0.1.0-SNAPSHOT.jar --http-only
```

## Architecture

GoatMosire is a hex map editor and MCP bridge for GSimulator (a turn-based grand strategy engine). Tech: Java 21, Maven, **no Spring** — pure `jdk.httpserver` for HTTP, stdio JSON-RPC for MCP, vanilla JS + HTML5 Canvas for the frontend. Depends on `gsim-lib` (from `example/GSimulator`, bundles all GSimulator application code: Agent, WorldInfo, MCP, HTTP API, WebUI).

### Package layout

| Package | Role |
|---------|------|
| `com.goatmosire` | Entry point (`GoatMosireApp`) — CLI arg parsing, wires HTTP + MCP servers, embedded GSim API, optional mixed mode |
| `com.goatmosire.config` | `GoatMosireConfig` record — reads `goatmosire.worldsDir`, `goatmosire.port`, `goatmosire.gsimPort`, import dir from system properties or env vars |
| `com.goatmosire.http` | `GoatmosireHttpServer` (root /api/map prefix), `MapApiHandler` (REST endpoints for hex query, terrain blocks, pathway groups, contour, compression), `StaticFileHandler` (serves from classpath `/web`) |
| `com.goatmosire.map` | Data model: `MapData` (hex grid, terrain blocks, provinces, cities, pathways), `MapDiff` (node diff), `MapResolver` (parent-chain resolution), `MapStore` (JSON persistence), `MapStoreException` |
| `com.goatmosire.service` | Core logic: `MapService` (resolve, cache, saveFull/saveDiff, A* pathfinding, contour, terrain blocks, compression), `TerrainCanvas` (block-based terrain editor with overlap/hollow rules), `TerrainGeometry` (hex math), `LassoProcessor` (Bresenham lasso + flood fill), `ContourQueryEngine`, `MapGenerator`, `ContinentContour`, `ContourLayer`, `CompressionService`/`CompressionValidator`/`CompressedRegion` (region compression for rendering), `CheckpointService`, `NodeSyncService`, `SimplexNoise`, `TerrainBlockProcessor` |
| `com.goatmosire.mcp` | `McpServer` (stdio JSON-RPC 2.0), `McpToolRegistry` (24 tools prefixed `goatmosire_`, tool registration split into 5 sub-methods) |

### Data flow

```
browser (Canvas editor) ──HTTP──▶ MapApiHandler ──▶ MapService ──▶ map package (MapResolver/MapStore/MapData)
                                        │                   │
AI agent ──MCP stdio──▶ McpServer ──────┘                   └── JSON files in worlds/{id}/nodes/
                     │
                     └── gsim_* tools → GsimMcpToolRegistry (embedded GSim API)
```

### Key concepts

- **MapData**: Record holding `hexes` (q_r → HexCell), `terrainBlocks`, `provinces`, `cities`, `rivers` (@Deprecated), `roads` (@Deprecated), `terrainTypes`, `pathwayGroups`, `compressedRegions`. HexCell has `color`, `terrain`, `symbol`, `symbolColor`, `description`, `riverMask` (6-bit edge mask), `edgeTags`. All collection accessors return unmodifiable copies (SpotBugs EI_EXPOSE_REP fix).
- **MapDiff**: Record of changed/removed hexes between parent and child nodes. Collection accessors return unmodifiable copies.
- **Diff inheritance**: Root node (n0000) stores full map; child nodes store `MapDiff` (only changed + removed hexes). `MapResolver` merges along the parent chain. Editor PUTs full map for root, auto-computes diff vs parent for child nodes.
- **TerrainBlock system**: Terrain stored as ordered polygon blocks (last = topmost). Overlap rules: same-terrain merges; different-terrain hollows (small inside big) or uses max-retention (big over small keeps both). To override: draw a same-terrain block that fully encircles and merges with outer same-terrain block.
- **Compression system**: `CompressionService` detects large contiguous same-terrain regions and creates `CompressedRegion` entries for efficient rendering. `CompressionValidator` transparently validates and repairs CR boundaries on every map load — hexes remain the authoritative data source.
- **riverMask**: River data stored per-hex as 6-bit edge mask (E=0,SE=1,SW=2,W=3,NW=4,NE=5). Rivers/Roads as separate arrays are **@Deprecated** — pathway groups are the modern replacement.
- **MapService cache**: LRU `LinkedHashMap` (32-entry max) keyed by `worldId/nodeId`. Evicts entire world prefix on mutation. Static `LruCache` inner class.
- **MCP protocol**: Raw JSON-RPC 2.0 over stdio, no library. Supports `initialize`, `tools/list`, `tools/call`. 24 GoatMosire tools prefixed `goatmosire_` for Hermes auto-discovery, plus all GSim `gsim_*` tools via embedded `GsimMcpToolRegistry`.

### Code quality

Three plugins run during `mvn verify` phase:

| Plugin | Config | Role |
|--------|--------|------|
| **Spotless** | Palantir Java Format 2.50.0 | Auto-format Java, Markdown, POM |
| **Checkstyle** | `gsim_checks.xml` (10.26.1) | Code style — naming, Javadoc, whitespace, imports, method length |
| **SpotBugs** | Max effort, Low threshold | Static analysis — null checks, resource leaks, mutable exposure |

- All code is Javadoc'd on public methods with `@param`/`@return` tags
- Star imports are forbidden — use explicit imports
- Checkstyle `WhitespaceAround` configured with `allowEmpty*` properties to co-exist with Palantir format
- `@SuppressWarnings("EI_EXPOSE_REP2")` used where defensive copies are impractical (e.g. shared service references)

### Frontend notes

Static files under `src/main/resources/web/`: `index.html` (single-file SPA with Canvas rendering, ~large), `map-api.js` (fetch wrapper), `toolbar.html`. Served from classpath via `StaticFileHandler`. No build step, no framework — vanilla JS.

### Configuration

System properties: `goatmosire.worldsDir` (default `./worlds`), `goatmosire.port` (default 8711), `goatmosire.importDir`, `goatmosire.httpOnly=true`, `goatmosire.mcpOnly=true`, `goatmosire.gsimPort` (default 8710), `goatmosire.noGsim=true`. Env vars: `GOATMOSIRE_WORLDS_DIR`, `GOATMOSIRE_PORT`, `GOATMOSIRE_GSIM_PORT`.

### Design rules

- **No CLI-as-HTTP**: Handlers call MapService/gsim-core APIs directly, never shell out or construct CLI strings.
- **Prefer @Deprecated over deletion**: Backward-compat safety net — mark old code rather than removing files.
- **Hierarchical REST**: `/api/map/{worldId}/nodes/{nid}/...`
- **Same-package agent isolation**: When using parallel sub-agents, each package is assigned to exactly one agent to avoid edit conflicts.
- **Compile after all edits**: Sub-agents only edit files; compilation and testing happen centrally after all agents complete.

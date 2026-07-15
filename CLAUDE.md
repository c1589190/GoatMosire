# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Prerequisite: install gsim-core to local Maven repo (from sibling GSimulator project)
cd ../GSimulator && mvn install -N && mvn install -pl gsim-core -DskipTests && cd -

# Build (shaded JAR, main class: com.goatmosire.GoatMosireApp)
mvn package -DskipTests -q

# Run tests (JUnit 5)
mvn test

# Start — HTTP :8711 + MCP stdio (default):
java -Dgoatmosire.worldsDir=./worlds -jar target/goatmosire-0.1.0-SNAPSHOT.jar

# Start HTTP-only (worldsDir defaults to ./worlds if not specified):
java -jar target/goatmosire-0.1.0-SNAPSHOT.jar --http-only
```

## Architecture

GoatMosire is a hex map editor and MCP bridge for GSimulator (a turn-based grand strategy engine). Tech: Java 21, Maven, **no Spring** — pure `jdk.httpserver` for HTTP, stdio JSON-RPC for MCP, vanilla JS + HTML5 Canvas for the frontend. Depends on `gsim-core` (local Maven artifact from a sibling GSimulator repo).

### Package layout

| Package | Role |
|---------|------|
| `com.goatmosire` | Entry point (`GoatMosireApp`) — CLI arg parsing, wires HTTP + MCP servers, optional mixed mode |
| `com.goatmosire.config` | `GoatMosireConfig` record — reads `goatmosire.worldsDir`, `goatmosire.port` from system properties or env vars |
| `com.goatmosire.http` | `GoatmosireHttpServer` (root /api/map prefix), `MapApiHandler` (REST endpoints), `StaticFileHandler` (serves from classpath `/web`) |
| `com.goatmosire.service` | Core logic: `MapService` (resolve, cache, saveFull/saveDiff, A* pathfinding, contour, terrain blocks), `TerrainCanvas` (block-based terrain editor with overlap/hollow rules), `TerrainGeometry` (hex math), `LassoProcessor` (Bresenham lasso + flood fill), `ContourQueryEngine`, `MapGenerator`, `ContinentContour`, `ContourLayer` |
| `com.goatmosire.mcp` | `McpServer` (stdio JSON-RPC 2.0), `McpToolRegistry` (9 tools prefixed `goatmosire_`) |

### Data flow

```
browser (Canvas editor) ──HTTP──▶ MapApiHandler ──▶ MapService ──▶ gsim-core (MapResolver/MapStore)
                                        │                   │
AI agent ──MCP stdio──▶ McpServer ──────┘                   └── JSON files in worlds/{id}/nodes/
```

### Key concepts

- **MapData**: gsim-core record holding `hexes` (q_r → HexCell), `terrainBlocks`, `provinces`, `cities`, `rivers`, `roads`, `terrainTypes`. HexCell has `color`, `terrain`, `symbol`, `symbolColor`, `description`, `riverMask` (6-bit edge mask).
- **Diff inheritance**: root node (n0000) stores full map; child nodes store `MapDiff` (only changed + removed hexes). `MapResolver` merges along the parent chain. Editor PUTs full map for root, auto-computes diff vs parent for child nodes.
- **TerrainBlock system**: terrain stored as ordered polygon blocks (last = topmost). Overlap rules: same-terrain merges; different-terrain hollows (small inside big) or uses max-retention (big over small keeps both). To override: draw a same-terrain block that fully encircles and merges with outer same-terrain block.
- **riverMask**: river data stored per-hex as 6-bit edge mask (E=0,SE=1,SW=2,W=3,NW=4,NE=5), not as a separate river array.
- **MapService cache**: LRU `LinkedHashMap` (32-entry max) keyed by `worldId/nodeId`. Evicts entire world prefix on mutation.
- **MCP protocol**: raw JSON-RPC 2.0 over stdio, no library. Supports `initialize`, `tools/list`, `tools/call`. Tools prefixed `goatmosire_` for Hermes auto-discovery.

### Frontend notes

Static files under `src/main/resources/web/`: `index.html` (single-file SPA with Canvas rendering, ~large), `map-api.js` (fetch wrapper), `toolbar.html`. Served from classpath via `StaticFileHandler`. No build step, no framework — vanilla JS.

### Configuration

System properties: `goatmosire.worldsDir` (default `./worlds`), `goatmosire.port` (default 8711), `goatmosire.httpOnly=true`, `goatmosire.mcpOnly=true`. Env vars: `GOATMOSIRE_WORLDS_DIR`, `GOATMOSIRE_PORT`.

### Design rules

- **No CLI-as-HTTP**: handlers call MapService/gsim-core APIs directly, never shell out or construct CLI strings.
- **Prefer @Deprecated over deletion**: backward-compat safety net — mark old code rather than removing files.
- **Hierarchical REST**: `/api/map/{worldId}/nodes/{nid}/...`

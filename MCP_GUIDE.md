# GoatMosire MCP 服务使用指南

GoatMosire 是一个 GSimulator 的六边形地图编辑器，通过 **MCP (Model Context Protocol)** 暴露地图查询与区域编辑能力，供 AI Agent 软件（Claude Code、Codex、Cline 等）直接调用。

## 1. 启动 MCP 服务

### 前置条件

GoatMosire 需要 GSimulator 世界数据。默认从 `./worlds` 目录读取。

```bash
# 构建（shaded JAR）
cd GoatMosire && mvn package -DskipTests -q

# 启动 MCP 服务（默认 stdio 模式，HTTP + MCP 双开）
java -Dgoatmosire.worldsDir=/path/to/GSimulator/worlds -jar target/goatmosire-0.1.0-SNAPSHOT.jar

# 仅 MCP 模式（无 HTTP）
java -Dgoatmosire.worldsDir=/path/to/GSimulator/worlds -jar target/goatmosire-0.1.0-SNAPSHOT.jar --mcp-only
```

### 配置 Agent 软件

在 Agent 软件的 MCP 配置中添加：

```json
{
  "mcpServers": {
    "goatmosire": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-Dgoatmosire.worldsDir=./worlds",
        "-jar",
        "/path/to/GoatMosire/target/goatmosire-0.1.0-SNAPSHOT.jar",
        "--mcp-only"
      ]
    }
  }
}
```

> 具体配置文件路径因 Agent 软件而异，常见: `.claude/mcp.json`、`.codex/mcp.json`、`mcp.json` 等。

## 2. 协议说明

GoatMosire 实现 **JSON-RPC 2.0** 协议，通过 **stdio** 通信：

- **每行一个 JSON 对象**（换行分隔）
- 请求-响应模式：发送 JSON-RPC 请求 → 接收 JSON-RPC 响应
- 支持 3 种 MCP 方法: `initialize`, `tools/list`, `tools/call`

### MCP 协议版本

`2024-11-05`

### 初始化

```
→ {"jsonrpc":"2.0","id":"1","method":"initialize","params":{...}}
← {"jsonrpc":"2.0","id":"1","result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"GoatMosire","version":"0.1.0"}}}
```

## 3. 工具列表

所有工具均以 `goatmosire_` 为前缀。共 **22 个工具**，分为查询类、写入类、checkpoint 文档类和初始化类：

### 查询工具

| 工具名 | 描述 | 必需参数 |
|--------|------|----------|
| `goatmosire_get_hex` | 查询单个六边形的详细信息（地形、产出、所属区域） | `worldId`, `q`, `r` |
| `goatmosire_get_province` | 查询区域的完整信息（所有格子、邻接区域、地形构成） | `worldId`, `name` |
| `goatmosire_get_neighbors` | 获取指定坐标的 6 个相邻格子 | `worldId`, `q`, `r` |
| `goatmosire_query_radius` | 查询半径范围内所有六边形 | `worldId`, `q`, `r`, `radius` |
| `goatmosire_get_cities` | 列出地图上所有城市及其坐标 | `worldId` |
| `goatmosire_get_diff` | 获取指定节点相比父节点的变更 | `worldId`, `nodeId` |
| `goatmosire_get_history` | 获取世界完整的历史节点链 | `worldId` |
| `goatmosire_find_river_path` | 寻找到最近水域的最小代价路径 | `worldId`, `q`, `r` |
| `goatmosire_list_regions` | 列出所有区域及其中心坐标、地形构成、邻接关系 | `worldId` |
| `goatmosire_get_distance` | 计算两点或两区域间的六边形距离 | `worldId`, (坐标或区域名) |

### 写入工具

| 工具名 | 描述 |
|--------|------|
| `goatmosire_create_region` | 创建新区（可选初始格子列表） |
| `goatmosire_delete_region` | 删除区域 |
| `goatmosire_rename_region` | 一站式改名：MapData + 所有 checkpoint 引用同步更新 |

### 初始化工具

| 工具名 | 描述 | 必需参数 |
|--------|------|----------|
| `goatmosire_init_nation` | 一键创建国家：flood-fill 领土 + 势力设定 + 开局推文 + 首都 | `worldId`, `name`, `seedQ`, `seedR` |

### Checkpoint 文档工具

| 工具名 | 描述 | 必需参数 |
|--------|------|----------|
| `goatmosire_list_checkpoints` | 列出节点所有 checkpoint 及其元素数量 | `worldId` |
| `goatmosire_get_checkpoint` | 查询 checkpoint 中的元素（支持 key/tags 过滤） | `worldId`, `checkpoint` |
| `goatmosire_add_checkpoint_element` | 向 checkpoint 添加新元素 | `worldId`, `checkpoint`, `key`, `value` |
| `goatmosire_update_checkpoint_element` | 更新已有 checkpoint 元素 | `worldId`, `checkpoint`, `key` |
| `goatmosire_delete_checkpoint_element` | 删除 checkpoint 元素 | `worldId`, `checkpoint`, `key` |
| `goatmosire_update_region` | 更新区域属性（标签、描述、颜色、格子列表） |
| `goatmosire_add_hex_to_region` | 向区域添加一个六边形 |
| `goatmosire_remove_hex_from_region` | 从区域移除一个六边形 |

> **通用可选参数**: 所有工具都接受 `nodeId`（字符串，可选），不传则使用世界的活跃节点。

## 4. 工具详细说明

### 4.1 `goatmosire_get_hex` — 查询单个六边形

```json
{"worldId": "logdemo", "q": 10, "r": -5}
```

返回:
```json
{
  "found": true,
  "q": 10, "r": -5,
  "color": "#5B8C3E",
  "terrain": "lowland",
  "food": 3, "gold": 1, "stone": 1, "moveCost": 1,
  "province": "大汉"
}
```

### 4.2 `goatmosire_get_province` — 查询区域详情

```json
{"worldId": "logdemo", "name": "大汉"}
```

返回:
```json
{
  "found": true, "name": "大汉",
  "hexCount": 650, "tag": "Nation",
  "center": {"q": 17, "r": 30},
  "terrainComposition": {"lowland": 307, "hills": 303, "plains": 40},
  "adjacentRegions": [
    {"name": "区域12", "sharedEdges": 18},
    {"name": "区域13", "sharedEdges": 17}
  ],
  "adjacentCount": 2,
  "hexes": [{"q": 10, "r": 20, "color": "#66ccd5", "terrain": "lowland"}, ...],
  "description": "..."
}
```

### 4.3 `goatmosire_list_regions` — 列出所有区域

```json
{"worldId": "logdemo"}
```

返回:
```json
{
  "worldId": "logdemo",
  "count": 22,
  "regions": [
    {
      "name": "大汉", "tag": "Nation", "hexCount": 650,
      "center": {"q": 17, "r": 30},
      "terrainComposition": {"lowland": 307, "hills": 303, "plains": 40},
      "adjacentRegions": [{"name": "区域12", "sharedEdges": 18}],
      "adjacentCount": 2
    },
    ...
  ]
}
```

### 4.4 `goatmosire_query_radius` — 半径查询

```json
{"worldId": "logdemo", "q": 10, "r": -5, "radius": 3}
```

返回中心坐标、半径、以及半径范围内所有存在六边形的列表。

### 4.5 `goatmosire_find_river_path` — 河流路径规划

使用 Dijkstra 算法，以地形的 `moveCost` 为边权重，找到通往最近水域的最小代价路径。

```json
{"worldId": "logdemo", "q": 12, "r": 34}
```

返回：
```json
{
  "source": {"q": 12, "r": 34},
  "path": ["12_34", "12_33", "11_32", "10_31"],
  "length": 4
}
```

### 4.6 `goatmosire_get_distance` — 计算距离

支持两种方式：

**按坐标**:
```json
{"worldId": "logdemo", "fromQ": 10, "fromR": 20, "toQ": 30, "toR": 40}
```

**按区域名**（使用区域中心点）:
```json
{"worldId": "logdemo", "fromRegion": "大汉", "toRegion": "法蒂玛哈里发国"}
```

返回:
```json
{
  "from": "大汉", "to": "法蒂玛哈里发国",
  "fromCoord": {"q": 17, "r": 30},
  "toCoord": {"q": -70, "r": -8},
  "hexDistance": 95
}
```

### 4.7 写入工具示例

**创建新区**:
```json
{"worldId": "logdemo", "name": "新王国", "tag": "Nation", "color": "#ff6600", "description": "一个新生的王国"}
```

**向区域添加格子**:
```json
{"worldId": "logdemo", "name": "大汉", "q": 18, "r": 31}
```

**更新区域标签和描述**:
```json
{"worldId": "logdemo", "name": "大汉", "tag": "Nation", "description": "更新后的描述文本"}
```

> 写入操作会自动保存地图并同步到 GSim 节点。

### 4.8 `goatmosire_rename_region` — 一站式改名

重命名区域时同步更新 **所有数据层**：MapData provinces + map checkpoint + factions checkpoint + narrative checkpoint（key、tags、value 文本引用全部更新）。

```json
{"worldId": "logdemo", "oldName": "区域16", "newName": "灰崖伯国"}
```

返回:
```json
{"ok": true, "oldName": "区域16", "newName": "灰崖伯国"}
```

### 4.9 `goatmosire_init_nation` — 一键初始化国家

从种子坐标自动 flood-fill 无归属领土，创建 MapData 区域，同步 GSim map checkpoint，**同时写入势力设定、开局推文、世界观、首都**——一次调用完成全部初始化。

```json
{
  "worldId": "logdemo",
  "name": "蜀汉",
  "seedQ": 50, "seedR": -80,
  "tag": "Nation",
  "color": "#cc8866",
  "maxHexes": 300,
  "faction": "名称: 蜀汉\n首都: 成都\n体制: 军阀割据\n统治者: 刘备\n宗教: 道教...",
  "narrative": "【蜀汉 · 开局推文】\n建兴三年，丞相诸葛亮...",
  "capital": "成都",
  "ruler": "刘备",
  "religion": "道教"
}
```

参数说明：

| 参数 | 必需 | 说明 |
|------|------|------|
| `worldId`, `name`, `seedQ`, `seedR` | ✅ | 世界ID、国名、种子坐标 |
| `faction` | 推荐 | 势力设定文本 → `factions` checkpoint |
| `narrative` | 推荐 | 开局推文 → `narrative` checkpoint |
| `capital` | 可选 | 首都名 → `map` checkpoint city 元素 |
| `worldview` | 可选 | 世界观文本 → `worldview` checkpoint |
| `tag` | 可选 | 区域标签（默认 `"Nation"`） |
| `color` | 可选 | 区域颜色（默认随机生成） |
| `maxHexes` | 可选 | flood-fill 上限（默认 1000） |
| `ruler`, `religion` | 可选 | 作为 tags 附加到 checkpoint 元素 |

返回:
```json
{
  "ok": true, "name": "蜀汉",
  "hexCount": 287, "color": "#cc8866",
  "center": {"q": 50, "r": -80},
  "checkpointsCreated": [
    "factions:蜀汉",
    "narrative:蜀汉开局",
    "map:City:成都"
  ]
}
```

> **注意**: seedQ/seedR 必须是地图上存在且**无归属**的 hex。若已属于其他区域会报错。flood-fill 遇到现有区域边界或地图边缘停止。

### 4.10 Checkpoint（文档）工具 — 读写 GSim 节点内容

这组工具用于读写 GSim 节点的 **checkpoint 数据**：叙事推文、势力设定、世界观文档、角色状态等。

**`goatmosire_list_checkpoints`** — 列出所有 checkpoint

```json
{"worldId": "logdemo"}
```

返回:
```json
{
  "worldId": "logdemo", "nodeId": "n0000",
  "checkpoints": [
    {"name": "narrative", "label": "推文", "elementCount": 3},
    {"name": "factions", "label": "factions", "elementCount": 4},
    {"name": "worldview", "label": "世界观", "elementCount": 3},
    {"name": "characters", "label": "characters", "elementCount": 1},
    {"name": "map", "label": "地图信息", "elementCount": 55}
  ]
}
```

**`goatmosire_get_checkpoint`** — 查询 checkpoint 内容

支持按 `key` 精确查找或按 `tags` 过滤（元素必须包含所有指定 tag）：

```json
// 获取所有推文
{"worldId": "logdemo", "checkpoint": "narrative"}

// 按 tag 过滤：只获取法蒂玛哈里发国的推文
{"worldId": "logdemo", "checkpoint": "narrative", "tags": ["法蒂玛哈里发国"]}

// 按 key 精确查找
{"worldId": "logdemo", "checkpoint": "factions", "key": "大汉"}
```

返回:
```json
{
  "worldId": "logdemo", "nodeId": "n0000",
  "checkpoint": "narrative", "label": "推文",
  "elements": [
    {
      "key": "法蒂玛开局", "type": "text",
      "value": "【法蒂玛哈里发国 · 开局推文】\n哈里发穆罕默德...",
      "tags": ["开局", "法蒂玛哈里发国", "推文", "turn0"],
      "links": []
    }
  ],
  "count": 1
}
```

**`goatmosire_add_checkpoint_element`** — 添加新文档

```json
{
  "worldId": "logdemo",
  "checkpoint": "narrative",
  "key": "新事件",
  "value": "帕拉丁帝国向西部边境增兵，引起了邻国的不安...",
  "tags": ["事件", "帕拉丁帝国", "turn1"]
}
```

**`goatmosire_update_checkpoint_element`** — 更新已有文档

```json
{
  "worldId": "logdemo",
  "checkpoint": "factions",
  "key": "大汉",
  "value": "更新后的大汉势力描述...",
  "tags": ["势力", "帝国", "Nation", "大汉"]
}
```

**`goatmosire_delete_checkpoint_element`** — 删除文档

```json
{"worldId": "logdemo", "checkpoint": "narrative", "key": "旧事件"}
```

> **常见 checkpoint 名称**: `narrative`（推文）、`factions`（势力）、`worldview`（世界观）、`characters`（角色）、`map`（地图信息）

## 5. 地形类型参考

GoatMosire 使用以下标准地形类型（可在世界数据中自定义）：

| 地形 | 颜色 | 食物 | 金币 | 石材 | 移动消耗 |
|------|------|------|------|------|----------|
| `lowland` | #5B8C3E | 3 | 1 | 1 | 1 |
| `plains` | #C4A84B | 2 | 2 | 1 | 1 |
| `hills` | #8B7355 | 1 | 1 | 3 | 2 |
| `mountain` | #9B9B9B | 0 | 0 | 4 | 4 |
| `water` | #3295D2 | 1 | 0 | 0 | 3 |
| `desert` | #D4C5A0 | 0 | 1 | 0 | 2 |
| `forest` | #2D6A3F | 2 | 0 | 2 | 2 |
| `tundra` | #B0C4D8 | 1 | 0 | 0 | 2 |

## 6. 坐标系统

GoatMosire 使用 **Axial 坐标系** (q, r)。六边形排布为 pointy-top：

```
      /\
     /  \
    |    |
     \  /
      \/

相邻方向:
  E (1,0), SE (1,-1), SW (0,-1), W (-1,0), NW (-1,1), NE (0,1)
```

六边形距离公式: `(abs(q1-q2) + abs(r1-r2) + abs((-q1-r1)-(-q2-r2))) / 2`

## 7. AI Agent 使用建议

### 典型工作流

1. **探索地图**: 用 `list_regions` 获取所有区域概览 → 用 `get_province` 深入了解特定区域 → 用 `query_radius` 查看局部地图
2. **路径规划**: 用 `find_river_path` 计算河流路径 → 用 `get_distance` 衡量区域间距离
3. **编辑地图**: 用 `create_region` 创建新区 → 用 `add_hex_to_region` 逐步扩展 → 用 `update_region` 修改属性

### 注意事项

- 写入操作**立即保存**，请谨慎调用
- `worldId` 可从 HTTP API `GET /api/map` 获取可用世界列表
- `nodeId` 通常不需要传，系统自动使用活跃节点
- 大量查询时建议先调用 `list_regions` 了解全局，再做精细查询

## 8. 世界数据文件结构

```
worlds/{worldId}/
├── world.json              # 世界元数据 (id, name, createdAt)
├── active.json             # 当前活跃节点
└── nodes/
    ├── n0000.json          # 节点数据 (turn, worldTime, checkpoints)
    ├── n0000_map.json      # 该节点的完整地图（根节点）或 diff（子节点）
    ├── n0001.json
    ├── n0001_map.json
    └── ...
```

- **根节点 (n0000)** 存储完整地图 (`_map.json`)
- **子节点** 存储相对父节点的 diff（仅变更和删除的格子）
- 节点间通过 `parentId` 字段形成链
- `checkpoints` 中包含叙事、势力、角色、世界观等非地图数据

## 9. HTTP API 补充

除了 MCP，GoatMosire 还提供 HTTP REST API（默认端口 8711），适合需要直接 HTTP 调用的场景：

| 端点 | 说明 |
|------|------|
| `GET /api/map` | 列出所有世界 |
| `GET /api/map/{worldId}` | 获取完整地图 |
| `GET /api/map/{worldId}/nodes` | 列出所有节点 |
| `GET /api/map/{worldId}/history` | 获取历史链 |
| `PUT /api/map/{worldId}` | 保存地图 |
| `POST /api/map/{worldId}/generate` | 生成新世界 |

静态文件（地图编辑器前端）通过 `GET /` 直接提供。

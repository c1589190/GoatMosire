# GoatMosire MCP 服务使用指南

GoatMosire 是 GSimulator 的六边形地图编辑器 + MCP 网关，通过 **MCP (Model Context Protocol)** 同时暴露 **地图编辑**（22 个工具）和 **世界文档管理**（12 个 GSim 工具），供 AI Agent（Claude Code、Codex、Cline 等）直接调用。

---

## 1. 快速开始

### 1.1 构建 & 启动

```bash
# 构建
cd GoatMosire && mvn package -DskipTests -q

# 启动（HTTP + MCP 双开，worldsDir 默认 ./worlds）
java -Dgoatmosire.worldsDir=./worlds -jar target/goatmosire-0.1.0-SNAPSHOT.jar

# 仅 MCP
java -Dgoatmosire.worldsDir=./worlds -jar target/goatmosire-0.1.0-SNAPSHOT.jar --mcp-only
```

### 1.2 配置 Agent 软件

在 Agent 的 MCP 配置文件中添加（常见路径：`.claude/mcp.json`、`.codex/mcp.json`、`mcp.json`）：

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

## 2. MCP 协议

GoatMosire 实现 **JSON-RPC 2.0**，通过 **stdio** 通信，协议版本 `2024-11-05`。

### 2.1 线格式

**每行一个 JSON 对象**（换行分隔），不需要 Content-Length 头。

### 2.2 初始化

```
→ {"jsonrpc":"2.0","id":"1","method":"initialize","params":{"protocolVersion":"0.1","clientInfo":{"name":"my-agent","version":"1.0"}}}
← {"jsonrpc":"2.0","id":"1","result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"GoatMosire","version":"0.1.0"}}}
```

### 2.3 列出所有工具

```
→ {"jsonrpc":"2.0","id":"2","method":"tools/list"}
← {"jsonrpc":"2.0","id":"2","result":{"tools":[{...}, ...]}}  // 34 个工具
```

### 2.4 调用工具

```
→ {"jsonrpc":"2.0","id":"3","method":"tools/call","params":{"name":"goatmosire_get_hex","arguments":{"worldId":"logdemo","q":10,"r":-5}}}
← {"jsonrpc":"2.0","id":"3","result":{"content":[{"type":"text","text":"{...}"}]}}
```

---

## 3. 工具总览（51 个）

| 前缀 | 职责 | 数量 |
|------|------|------|
| `goatmosire_` | 地图查询 / 编辑 / 区域管理 / checkpoint 读写 | 22 |
| `gsim_` | 世界浏览 / 搜索 / @ref / 文档 / LLM / Agent / 配置 | 29 |

`nodeId` 在所有工具中都是**可选参数**——不传则使用世界的活跃节点。

---

## 4. GoatMosire 地图工具 (`goatmosire_`)

### 4.1 查询类（10 个）

| 工具 | 描述 | 必需参数 |
|------|------|----------|
| `get_hex` | 查询单个六边形（地形、产出、所属区域） | `worldId`, `q`, `r` |
| `get_neighbors` | 获取 6 个相邻格子 | `worldId`, `q`, `r` |
| `query_radius` | 半径范围内所有六边形 | `worldId`, `q`, `r`, `radius` |
| `get_province` | 区域详情（所有格子、邻接、地形构成） | `worldId`, `name` |
| `list_regions` | 全部区域（中心坐标、邻接关系、地形构成） | `worldId` |
| `get_cities` | 所有城市及其坐标 | `worldId` |
| `get_distance` | 六边形距离（坐标或区域名） | `worldId` + 坐标/区域名 |
| `get_diff` | 指定节点的地图变更 | `worldId`, `nodeId` |
| `get_history` | 历史节点链 | `worldId` |
| `find_river_path` | 到最近水域的最小代价路径（Dijkstra） | `worldId`, `q`, `r` |

#### 示例：查询地图

```json
// 获取单个六边形
{"name":"goatmosire_get_hex","arguments":{"worldId":"logdemo","q":10,"r":-5}}
// → {"found":true,"q":10,"r":-5,"color":"#C5B358","terrain":"plains","food":2,"gold":2,"stone":1,"moveCost":1,"province":null}

// 查询区域详情
{"name":"goatmosire_get_province","arguments":{"worldId":"logdemo","name":"大汉"}}
// → {"found":true,"name":"大汉","hexCount":650,"tag":"Nation","center":{"q":17,"r":30},
//    "terrainComposition":{"lowland":307,"hills":303,"plains":40},
//    "adjacentRegions":[{"name":"区域12","sharedEdges":23},...],"adjacentCount":7,...}

// 计算距离
{"name":"goatmosire_get_distance","arguments":{"worldId":"logdemo","fromRegion":"大汉","toRegion":"奥斯曼帝国"}}
// → {"from":"大汉","to":"奥斯曼帝国","hexDistance":119,"fromCoord":{"q":17,"r":30},"toCoord":{"q":8,"r":-80}}

// 半径查询
{"name":"goatmosire_query_radius","arguments":{"worldId":"logdemo","q":0,"r":0,"radius":2}}
// → {"center":{"q":0,"r":0},"radius":2,"count":19,"hexes":[...]}
```

### 4.2 写入类（7 个）

| 工具 | 描述 |
|------|------|
| `create_region` | 创建新区（可指定初始格子列表、tag、颜色） |
| `delete_region` | 删除区域 |
| `update_region` | 更新属性（tag、描述、颜色、格子列表） |
| `add_hex_to_region` | 向区域添加一个六边形 |
| `remove_hex_from_region` | 从区域移除一个六边形 |
| `rename_region` | 一站式改名——MapData + 所有 checkpoint 引用同步更新 |
| `update_terrain_type` | 修改地形定义（产出、颜色、移动消耗） |

#### 示例：创建新区域

```json
{"name":"goatmosire_create_region","arguments":{
  "worldId":"logdemo","name":"新王国","tag":"Nation","color":"#ff6600",
  "description":"一个新生的王国","hexes":["50_30","51_30","50_31"]
}}
// → {"ok":true,"name":"新王国","hexCount":3,...}
```

### 4.3 Checkpoint 文档类（5 个）

| 工具 | 描述 | 必需参数 |
|------|------|----------|
| `list_checkpoints` | 列出节点所有 checkpoint | `worldId` |
| `get_checkpoint` | 查询元素（支持 key 精确查找、tags 过滤） | `worldId`, `checkpoint` |
| `add_checkpoint_element` | 添加文档条目 | `worldId`, `checkpoint`, `key`, `value` |
| `update_checkpoint_element` | 更新已有元素 | `worldId`, `checkpoint`, `key` |
| `delete_checkpoint_element` | 删除元素 | `worldId`, `checkpoint`, `key` |

#### 示例：读写叙事推文

```json
// 列出 checkpoints
{"name":"goatmosire_list_checkpoints","arguments":{"worldId":"logdemo"}}
// → {"checkpoints":[{"name":"narrative","label":"推文","elementCount":6},...]}

// 按 tags 过滤
{"name":"goatmosire_get_checkpoint","arguments":{"worldId":"logdemo","checkpoint":"narrative","tags":["大汉"]}}
// → {"elements":[{"key":"大汉开局","value":"【大汉 · 开局推文】\n永平七年，春。\n...","tags":["开局","大汉","推文","turn0"]}],...}

// 添加新推文
{"name":"goatmosire_add_checkpoint_element","arguments":{
  "worldId":"logdemo","checkpoint":"narrative","key":"新事件",
  "value":"帕拉丁帝国向西部边境增兵...","tags":["事件","帕拉丁帝国","turn1"]
}}
```

### 4.4 初始化工具（1 个）

| 工具 | 描述 | 必需参数 |
|------|------|----------|
| `init_nation` | 一键创建国家：flood-fill 领土 + 势力设定 + 推文 + 首都 | `worldId`, `name`, `seedQ`, `seedR` |

```json
{"name":"goatmosire_init_nation","arguments":{
  "worldId":"logdemo","name":"蜀汉","seedQ":50,"seedR":-80,
  "faction":"名称: 蜀汉\n首都: 成都\n体制: 军阀割据\n...",
  "narrative":"【蜀汉 · 开局推文】\n建兴三年...",
  "capital":"成都","ruler":"刘备","religion":"道教"
}}
// → {"ok":true,"name":"蜀汉","hexCount":287,"checkpointsCreated":["factions:蜀汉","narrative:蜀汉开局","map:City:成都"]}
```

---

## 5. GSim 世界管理工具 (`gsim_`)

这些工具直接读取 GSim 节点 JSON 文件，独立于 GoatMosire 的 HTTP API，提供世界数据浏览、全文搜索、@ref 引用解析和文档管理能力。

### 5.1 世界概览类（3 个）

| 工具 | 描述 |
|------|------|
| `gsim_list_worlds` | 列出所有可用世界（含名称、创建时间、节点数） |
| `gsim_get_world_info` | 世界详情：名称、活跃节点、节点链、每节点 turn/worldTime/parentId |
| `gsim_get_node_info` | 单节点元数据：turn、worldTime、parentId、isRoot、所有 checkpoint 概览 |

```json
// 列出世界
{"name":"gsim_list_worlds","arguments":{}}
// → {"count":2,"worlds":[{"worldId":"logdemo","name":"日志演示","createdAt":"...","nodeCount":3},...]}

// 查看节点链
{"name":"gsim_get_world_info","arguments":{"worldId":"logdemo"}}
// → {"activeNode":"n0000","nodes":[{"nodeId":"n0000","turn":0,"worldTime":"时间原点","parentId":null,"hasMap":true},
//    {"nodeId":"n0001","turn":1,"worldTime":"星历501年秋","parentId":"n0000","hasMap":true}]}

// 节点元数据
{"name":"gsim_get_node_info","arguments":{"worldId":"logdemo","nodeId":"n0000"}}
// → {"turn":0,"worldTime":"时间原点","parentId":null,"isRoot":true,
//    "checkpoints":[{"name":"narrative","label":"推文","type":"narrative","elementCount":6},...]}
```

### 5.2 Checkpoint 查询类（2 个）

| 工具 | 描述 | 必需参数 |
|------|------|----------|
| `gsim_list_checkpoints` | 列出节点所有 checkpoint | `worldId` |
| `gsim_get_checkpoint` | 查询元素（支持 `key` 精确查找、`tags` 过滤、`limit` 分页） | `worldId`, `checkpoint` |

```json
// 获取 factions（前 2 条）
{"name":"gsim_get_checkpoint","arguments":{"worldId":"logdemo","checkpoint":"factions","limit":2}}
// → {"elements":[{"key":"魔法议会","value":"统治大陆的最高魔法机构",...},
//    {"key":"法蒂玛哈里发国","value":"名称: 法蒂玛哈里发国...",...}],"count":2}
```

### 5.3 Checkpoint 写入类（3 个）

| 工具 | 描述 |
|------|------|
| `gsim_add_checkpoint_element` | 添加元素（防重复 key，自动时间戳） |
| `gsim_update_checkpoint_element` | 更新元素（只改传入字段） |
| `gsim_delete_checkpoint_element` | 按 key 删除元素 |

```json
{"name":"gsim_add_checkpoint_element","arguments":{
  "worldId":"logdemo","checkpoint":"narrative","key":"测试事件",
  "value":"这是一条测试推文...","tags":["测试","turn1"]
}}
// → {"ok":true,"key":"测试事件","type":"text"}
```

### 5.4 搜索 & 引用（2 个）

| 工具 | 描述 | 必需参数 |
|------|------|----------|
| `gsim_search` | 全文搜索——跨所有世界的 checkpoint + 导入文档 | `query` |
| `gsim_resolve_ref` | 解析 @ref 引用到完整内容 | `ref` |

#### 搜索示例

```json
// 全局搜索
{"name":"gsim_search","arguments":{"query":"洛阳","scope":"world","limit":3}}
// → {"results":[{"source":"world","worldId":"logdemo","checkpoint":"narrative","key":"大汉开局",
//    "excerpt":"...洛阳港的商船队刚从中土诸国返航...","ref":"@world:n0000:narrative:大汉开局"},...]}

// 限定世界
{"name":"gsim_search","arguments":{"query":"法蒂玛","scope":"world","worldId":"logdemo"}}

// 搜索文档
{"name":"gsim_search","arguments":{"query":"API","scope":"docs"}}
```

#### 引用解析

支持 **3 种格式**：

```
@world:worldId:nodeId:checkpoint:key     (5 段，最精确)
@world:worldId:checkpoint:key            (4 段，节点默认活跃)
@world:checkpoint:key                    (3 段，世界+节点均默认)
@doc:docId                               (文档引用)
```

```json
// 精确引用
{"name":"gsim_resolve_ref","arguments":{"ref":"@world:logdemo:n0000:narrative:大汉开局"}}
// → {"source":"world","key":"大汉开局","value":"【大汉 · 开局推文】\n永平七年，春。\n...",
//    "tags":["开局","大汉","推文","turn0"],"ref":"@world:n0000:narrative:大汉开局"}

// 简写引用
{"name":"gsim_resolve_ref","arguments":{"ref":"@world:logdemo:factions:大汉"}}
// → 自动使用活跃节点 → 同上

// 文档引用
{"name":"gsim_resolve_ref","arguments":{"ref":"@doc:GSimulator-HTTP-API-Guide.md"}}
// → {"source":"doc","content":"# GSimulator HTTP API 使用说明书...","ref":"@doc:..."}
```

### 5.5 文档管理类（2 个）

| 工具 | 描述 |
|------|------|
| `gsim_list_docs` | 列出所有导入文档（支持关键词过滤） |
| `gsim_get_doc` | 读取文档内容（支持 offset/limit 分页） |

```json
// 列出文档
{"name":"gsim_list_docs","arguments":{}}
// → {"count":23,"docs":[{"docId":"设定.md","size":65},...]}

// 分页读取
{"name":"gsim_get_doc","arguments":{"docId":"设定.md","offset":0,"limit":200}}
// → {"totalLines":5,"content":"# 世界观设定\n...","ref":"@doc:设定.md"}
```

### 5.6 LLM Provider 管理（6 个）

> 通过 HTTP 转发调用 GSimulator 的 `/api/llm` 端点。需要 GSimulator (`gsim-app`) 运行在 `http://127.0.0.1:8710`。

| 工具 | 描述 | 必需参数 |
|------|------|----------|
| `gsim_llm_list` | 列出所有已配置的 LLM Provider | — |
| `gsim_llm_get` | 查看指定 Provider 详情 | `id` |
| `gsim_llm_add` | 添加新 Provider | `id`, `baseUrl`, `model` |
| `gsim_llm_update` | 修改 Provider 字段 | `id`, `field`, `value` |
| `gsim_llm_delete` | 删除 Provider | `id` |
| `gsim_llm_test` | 测试 Provider 连通性 | `id` |

```json
// 添加新 Provider
{"name":"gsim_llm_add","arguments":{"id":"deepseek","baseUrl":"https://api.deepseek.com/v1","model":"deepseek-chat","apiKey":"sk-..."}}
// → {"status":200,"data":{"ok":true}}

// 测试连通性
{"name":"gsim_llm_test","arguments":{"id":"deepseek"}}
// → {"status":200,"data":{"ok":true,"latencyMs":234}}
```

### 5.7 Agent 生命周期管理（5 个）

> 通过 HTTP 转发调用 GSimulator 的 `/api/agents` 端点。

| 工具 | 描述 | 必需参数 |
|------|------|----------|
| `gsim_agent_list` | 列出所有 Agent 实例及状态 | — |
| `gsim_agent_get` | 查看指定 Agent 详情 | `instanceId` |
| `gsim_agent_run` | 启动新 Agent（异步） | `sessionId`, `input` |
| `gsim_agent_cancel` | 取消运行中的 Agent | `instanceId` |
| `gsim_agent_output` | 获取已完成 Agent 的输出 | `instanceId` |

```json
// 启动 Agent 推演
{"name":"gsim_agent_run","arguments":{"sessionId":"default","input":"查看大汉的当前局势并给出战略建议","agentConfig":"strategist"}}
// → {"instanceId":"agent-abc123","status":"RUNNING"}
```

### 5.8 Agent 配置管理（5 个）

> 通过 HTTP 转发调用 GSimulator 的 `/api/agent-configs` 端点。

| 工具 | 描述 | 必需参数 |
|------|------|----------|
| `gsim_agent_config_list` | 列出所有 Agent 配置 | — |
| `gsim_agent_config_get` | 查看指定配置详情 | `configId` |
| `gsim_agent_config_create` | 创建新配置 | `configId`, `persona` |
| `gsim_agent_config_update` | 更新配置字段 | `configId` |
| `gsim_agent_config_delete` | 删除配置 | `configId` |

```json
// 创建策略顾问配置
{"name":"gsim_agent_config_create","arguments":{"configId":"strategist","name":"战略顾问","persona":"你是一位精通地缘政治与军事战略的顾问...","model":"deepseek-chat","temperature":0.3,"toolGroups":["simulation","knowledge","branch_memory"]}}
```

---

## 6. 典型工作流

### 6.1 探索新世界

```
gsim_list_worlds                    → 发现可用世界
gsim_get_world_info(worldId)        → 了解节点链
goatmosire_list_regions(worldId)    → 获取地图全局
goatmosire_get_province(name)       → 深入了解特定区域
gsim_get_checkpoint(factions)       → 了解势力设定
gsim_resolve_ref(@world:...:大汉)   → 读取大汉叙事
```

### 6.2 跨工具信息链

```
goatmosire_get_hex → 发现某格属于"大沪王朝"
    ↓
gsim_resolve_ref(@world:...:map:Nation:大沪王朝) → 查该区域的地图 checkpoint 详情
    ↓
gsim_search("大沪") → 搜索所有相关推文/设定
    ↓
gsim_resolve_ref(@world:...:factions:大沪王朝) → 读取势力设定全文
```

### 6.3 创建新国家（一步到位）

```
goatmosire_init_nation(name, seedQ, seedR, faction, narrative, capital)
    → flood-fill 领土 + 写入 factions/narrative/map 三个 checkpoint
    → 返回中心坐标和创建的元素列表
```

### 6.4 LLM/Agent 配置与推演

```
gsim_llm_list                    → 查看已有 LLM Provider
gsim_llm_add(id,url,model,key)   → 配置新模型
gsim_llm_test(id)                → 测试连通性
    ↓
gsim_agent_config_create(...)    → 创建 Agent 配置（角色、工具组）
gsim_agent_config_list           → 确认配置就绪
    ↓
gsim_agent_run(session, input)   → 启动推演 Agent
gsim_agent_get(instanceId)       → 轮询状态
gsim_agent_output(instanceId)    → 获取推演结果
```

---

| 地形 | 颜色 | 食物 | 金币 | 石材 | 移动消耗 |
|------|------|------|------|------|----------|
| `lowland` | `#5B8C3E` | 3 | 1 | 1 | 1 |
| `plains` | `#C4A84B` | 2 | 2 | 1 | 1 |
| `hills` | `#8B7355` | 1 | 1 | 3 | 2 |
| `mountain` | `#9B9B9B` | 0 | 0 | 4 | 4 |
| `water` | `#3295D2` | 1 | 0 | 0 | 3 |
| `desert` | `#D4C5A0` | 0 | 1 | 0 | 2 |
| `forest` | `#2D6A3F` | 2 | 0 | 2 | 2 |
| `tundra` | `#B0C4D8` | 1 | 0 | 0 | 2 |
| `swamp` | `#5C6B4A` | 1 | 0 | 0 | 3 |

可通过 `goatmosire_update_terrain_type` 自定义。

---

## 8. 坐标系统

**Axial 坐标系** (q, r)，pointy-top 六边形排列。

```
        /\
       /  \
      |    |
       \  /
        \/

相邻方向: E(1,0)  SE(1,-1)  SW(0,-1)  W(-1,0)  NW(-1,1)  NE(0,1)
距离公式: (|q1-q2| + |r1-r2| + |(-q1-r1) - (-q2-r2)|) / 2
```

---

## 9. 世界数据文件结构

```
worlds/{worldId}/
├── world.json              # 世界元数据
├── active.json             # 当前活跃节点
└── nodes/
    ├── n0000.json          # 节点（turn, worldTime, checkpoints）
    ├── n0000_map.json      # 地图（根节点=完整，子节点=diff）
    ├── n0001.json
    └── ...
```

每个节点 JSON 中的 checkpoint 结构：

```json
{
  "checkpoints": {
    "narrative": { "label": "推文", "type": "narrative", "elements": [...] },
    "factions":  { "label": "factions", "type": "misc", "elements": [...] },
    "worldview": { "label": "世界观", "type": "worldview", "elements": [...] },
    "characters":{ "label": "characters", "type": "misc", "elements": [...] },
    "map":       { "label": "地图信息", "type": "map", "elements": [...] }
  }
}
```

元素格式：`{key, type, value, tags[], links[], createdAt, updatedAt}`

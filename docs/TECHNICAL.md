# GoatMosire — Hex Map Editor for GSimulator

> 为国策文游而生的六边形地图编辑器。与 GSimulator 引擎深度集成，通过 HTTP API 和 MCP stdio 协议为人类 GM 和 AI Agent 提供地图编辑与查询能力。

## 架构概览

```
┌─────────────────────────────────────────────────┐
│                   浏览器                          │
│  ┌───────────────────────────────────────────┐  │
│  │         index.html (单文件编辑器)            │  │
│  │  Canvas 渲染 · 工具系统 · 省份/河流编辑      │  │
│  └──────────────┬────────────────────────────┘  │
└─────────────────┼────────────────────────────────┘
                  │ HTTP :8711
┌─────────────────┼────────────────────────────────┐
│  GoatMosire     │                                │
│  ┌──────────────┴───────────────────────────┐   │
│  │         HTTP Server (jdk.httpserver)       │   │
│  │   MapApiHandler · StaticFileHandler       │   │
│  └──────────────────┬───────────────────────┘   │
│  ┌──────────────────┴───────────────────────┐   │
│  │              MapService                    │   │
│  │  resolve · saveFull · saveDiff · A* 寻路   │   │
│  └──────────────────┬───────────────────────┘   │
│  ┌──────────────────┴───────────────────────┐   │
│  │          McpToolRegistry (9 tools)         │   │
│  │  stdio JSON-RPC for Hermes & AI agents     │   │
│  └──────────────────┬───────────────────────┘   │
└─────────────────────┼────────────────────────────┘
                      │
┌─────────────────────┼────────────────────────────┐
│  GSimulator         │                            │
│  gsim-core          │                            │
│  ┌──────────────────┴───────────────────────┐   │
│  │  MapData · MapDiff · MapStore · MapResolver │  │
│  │  worlds/{id}/nodes/{nid}_map.json          │  │
│  └──────────────────────────────────────────┘   │
└─────────────────────────────────────────────────┘
```

## 构建 & 运行

```bash
# 1. 安装 gsim-core 到本地 Maven 仓库
cd GSimulator
mvn install -N && mvn install -pl gsim-core -DskipTests

# 2. 构建 GoatMosire
cd GoatMosire
mvn package -DskipTests -q

# 3. 启动（HTTP 模式，端口 8711）
java -jar target/goatmosire-0.1.0-SNAPSHOT.jar --http-only
# worldsDir 默认为 ./worlds，也可显式指定：
# java -Dgoatmosire.worldsDir=./worlds -jar ...

# 浏览器访问
xdg-open http://localhost:8711
```

## 数据模型

### HexCell（六边形地块）

```java
public record HexCell(
    String color,        // 填充色
    String terrain,      // 地形类型 (plains/forest/mountain/water/desert/swamp/tundra/hills)
    String symbol,       // 符号 (castle/city/等)
    String symbolColor,  // 符号颜色
    String description,  // 描述文本（可选）
    int riverMask        // 6-bit 边掩码: 0=E,1=SE,2=SW,3=W,4=NW,5=NE
)
```

### TerrainType（地形类型）

```java
public record TerrainType(
    String name,       // 标识名
    String color,      // 显示色
    int food,          // 🍖 食物产出
    int gold,          // 💰 金钱产出
    int stone,         // 🪨 石材产出
    int moveCost,      // 👣 通行成本 (1=平原, 3=山地, 99=水域)
    String description // 描述
)
```

### Province（省份/地块组）

```java
public record Province(
    List<String> hexes, // 包含的所有 hex key（边界 + 内部）
    String color,       // 显示色
    String tag,         // GSim tag 关联 (如 "nation.高卢")
    String description  // 描述
)
```

### River / Road（河流 / 道路）

```java
public record River(
    String name,          // 名称
    List<String> path,    // hex key 序列（可选，河流数据实际存储在 HexCell.riverMask 中）
    int width,            // 线宽
    String color          // 颜色
)
```

### 地图文件结构

```
worlds/{worldId}/nodes/
├── n0000.json              # 节点元数据 (turn, parentId)
├── n0000_map.json          # 根节点: 完整地图
├── n0001.json
├── n0001_map.json          # 子节点: diff (changed hexes + parentNodeId)
└── active.json             # 活跃节点 ID
```

**Diff 继承链**: n0000 (全量) ← n0001 (diff) ← n0002 (diff) ← ...

## HTTP API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/map` | 列出所有世界 |
| GET | `/api/map/{wid}` | 获取地图（默认活跃节点） |
| GET | `/api/map/{wid}?node={nid}` | 获取指定节点地图 |
| GET | `/api/map/{wid}/nodes` | 列出节点链 |
| GET | `/api/map/{wid}/history` | 变更历史 |
| GET | `/api/map/{wid}/river-path?q=&r=` | A* 河流寻路 |
| POST | `/api/map/{wid}` | 创建新地图（全量） |
| PUT | `/api/map/{wid}?node={nid}` | 保存地图（根节点=全量，子节点=自动算 diff） |
| GET | `/` | 编辑器 SPA |

## MCP 工具

供 Hermes Agent / AI 通过 stdio JSON-RPC 调用：

| 工具名 | 说明 |
|--------|------|
| `goatmosire_get_hex` | 查询单个 hex（q, r, terrain, riverMask, description, province） |
| `goatmosire_get_province` | 查询省份（hex 列表, tag, description） |
| `goatmosire_get_neighbors` | 查询相邻 hex |
| `goatmosire_query_radius` | 半径查询 |
| `goatmosire_get_cities` | 城市列表 |
| `goatmosire_get_diff` | 两节点间差异 |
| `goatmosire_get_history` | 历史记录 |
| `goatmosire_list_worlds` | 世界列表 |
| `goatmosire_find_river_path` | A* 寻路到水域 |

## 编辑器功能

### 地形绘制
- 🖊 **画笔**: 点击/拖拽涂地形，保留已有 riverMask/description
- 🪣 **填充**: 同色区域 flood fill，上限 5000 格，空地不填充
- 🧹 **擦除**: 删除格子

### 河流系统
- 🌊 **手动模式**: 点 A → 点相邻 B → 河流线段（中心到中心，白色描边+蓝色）
- **riverMask 存储**: 6-bit 边掩码嵌入 HexCell，方向 0=E,1=SE,2=SW,3=W,4=NW,5=NE
- **链式编辑**: B 成为新起点，继续延伸
- **退出**: 点起点退回/点非相邻格结束
- **自动寻路**: API 已就绪（A* 按 terrain.moveCost 选最优路径），编辑器可通过 Shift+点击启用

### 省份系统
- 🏛 **创建**: 点击圈边界 → 自动连线 → 点起点闭合 → flood fill 内部 → 命名
- **管理**: 右侧面板列表，选中高亮（半透明色填充 + 边界圆点）
- **拖拽编辑**: 边界圆点往外拖 = 扩张，往里拖 = 收缩
- **Tag 联动**: 省份可设 GSim tag（如 `nation.高卢`），通过 `goatmosire_get_province` 交叉查询

### 地图管理
- 世界选择器（下拉）
- 节点选择器（下拉，支持 auto 模式）
- localStorage 记住选择（跨刷新保持）

### 视口性能
- **视口裁剪**: 只渲染可见范围内的六边形
- **颜色批处理**: 同色格子合并为一次 draw call
- 状态栏显示渲染比例（>1000 格时）

## 渲染管线

```
render()
  ├─ hex fills (颜色批处理 + 视口裁剪)
  ├─ hex strokes (六边形边框)
  ├─ renderRivers()      ← hex 中心→中心线段, 白色描边+蓝色
  └─ renderProvinceHighlight() ← 省半透明填充 + 边界圆点
```

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 21, Maven (jdk.httpserver, no Spring) |
| 数据 | JSON 文件, Jackson 序列化 |
| 前端 | Vanilla JS + HTML5 Canvas, 单文件无构建 |
| 通信 | HTTP REST + MCP stdio JSON-RPC |
| 依赖 | gsim-core (GSimulator 多模块项目) |

## 设计原则

1. **anti-CLI-as-HTTP**: HTTP handler 直调底层 Manager/Service，不拼 CLI 命令字符串
2. **层级化 REST**: `/api/world/{id}/nodes/{nid}/{cpId}/{key}`
3. **Diff 继承**: 子节点只存变更，MapResolver 沿 parentId 链合并
4. **代码保留**: @Deprecated 标记废弃代码，不直接删文件
5. **riverMask**: 河流数据嵌入 hex 的 6-bit 边掩码，不另存数组；道路（roadMask）可复用同一机制

## 已知限制

- 河流渲染目前 60fps 重绘，超大河流（1000+ 段）可能轻微掉帧
- 省份拖拽无 undo/redo（可后续加 diff 链）
- 无道路自动寻路（API 结构已就绪）
- 无自动河流生成（高程/降雨模拟），仅 A* 按 moveCost 寻路
- 单文件 HTML 随着功能增加变得越来越长（当前 ~900 行），需考虑模块化

## 未来计划

### Phase A: 道路系统
- `HexCell.roadMask`（复用 riverMask 的 6-bit 结构）
- 道路工具 + 渲染（棕色线条，可选虚线）
- A* 寻路 API（`/road-path`）

### Phase B: 自动河流
- 高地点 → 水域自动寻路（A* 已就绪）
- 编辑器双模式切换：手动 / 自动
- 多支流合并检测

### Phase C: 编辑器增强
- Undo/Redo（利用 Diff 链回溯）
- 热键绑定（P=画笔, F=填充, E=擦除, R=河流, B=省份）
- 地形面板编辑器（添加/删除自定义地形类型）
- 编辑器模块化（拆分为多个 JS 文件）

### Phase D: 省份边界存储优化
- 只存边界 hex (6-7x 缩小存储)
- 重建算法: seed → flood fill → 完整省
- 套索工具替代当前的点击圈选

### Phase E: GSim 深度集成
- 省份 tag → GSim Element 双向查询
- 地图快照关联到 GSim turn
- MCP Agent 可通过自然语言操作地图

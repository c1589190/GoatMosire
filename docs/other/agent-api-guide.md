---
type: other
title: Agent API 引导手册
tags: [guide, api, agent]
version: 1
updated: 1784386557823
---
# GSimulator HTTP API — Agent 引导手册

> 你是一个外部 AI Agent，连接到一个回合制叙事推演引擎。  
> 核心原则：**用 @ 引用替代复制全文，用 text_edit 编辑管道替代来回搬运大段文本。**

---

## 连接

```
Base:   http://127.0.0.1:8710
Help:   GET /api/help        (本手册)
Status: GET /api/status
```

所有响应格式：`{"success": bool, "data": {...}, "error": {...}}`

中文参数需 **URL-encode**（curl 自动处理，代码中手动做）。

---

## @ 引用系统（省 Token 核心）

**永远不要复制全文，用 @ 引用。**

| 引用格式 | 含义 | 示例 |
|---------|------|------|
| `@world:n0002:characters:曹操` | World 元素（3段：节点:检查点:key） | 角色设定 |
| `@world:characters:曹操` | 同上（2段，默认活跃节点） | 同上 |
| `@doc:char_guanyu` | Doc 文档 | 角色/技能/模板 |
| `@cache:text_edit_xxx` | 缓存文本（大段输出短引用） | 编辑中间结果 |
| `@import:wiki_doc.txt` | 导入的外部文档 | 参考资料 |

**读取用 GET /api/ref**：
```
GET /api/ref?ref=@world:n0002:characters:曹操
→ {"source":"world", "title":"曹操 @n0002", "content":"全文..."}
```

**写入时直接用 @ 引用**（自动展开为全文）：
```json
POST /api/world/default/nodes/n0002/outcomes/result
{"value": "@cache:edit_20260704_xxx", "type": "narrative"}
```

**搜索用 GET /api/search**：
```
GET /api/search?q=曹操&scope=all   → world + doc + import 三源
GET /api/search?q=曹操&scope=world → 仅 world
```

---

## 文本编辑管道（不要复制粘贴）

### 流程

```
读文本 → 存 @cache: → 编辑 → 新 @cache: → 写入目标
```

### 1. 获取文本并存为 @cache:

```
GET /api/ref?ref=@world:n0002:characters:曹操
→ 内容超过 200 字符时自动返回 @cache:id

或手动：
POST /api/caches/{cacheId}/edit
{"select_lines": "0-999"}    # 保留全部行
→ {"newCacheId": "edit_xxx", "ref": "@cache:edit_xxx"}
```

### 2. 编辑文本（支持链式操作）

```
POST /api/caches/{cacheId}/edit

操作（按 select→delete→insert→replace_lines→replace_kw→mask 顺序执行）：

select_lines:    "1-6, 11-14"     # 保留指定行
delete_lines:    "7-10"           # 删除行
insert_at:       5                 # 在第5行前插入
insert_text:     "新内容"          # 配合 insert_at
replace_spec:    "3-4"            # 替换行范围
replace_text:    "替换后文本"      # 配合 replace_spec
replace_from:    "曹操,刘备"       # 关键词替换（两遍防重叠）
replace_to:      "曹公,刘皇叔"     # 一一对应
mask_kw:         "秘密,阴谋"       # 遮蔽为 ***
mask_lines_spec: "8-9"            # 整行遮蔽

→ 返回 {"newCacheId": "edit_xxx", "ref": "@cache:edit_xxx"}
```

### 3. 写回到任意目标

```
# 写入 World
POST /api/world/{id}/nodes/{nid}/{cpId}/{key}
{"value": "@cache:edit_xxx", "type": "text", "tags": "tag1,tag2"}

# 更新 Doc
PATCH /api/docs/{docId}
{"content": "@cache:edit_xxx"}

# 创建 Doc
POST /api/docs
{"docId": "new_doc", "type": "character", "title": "新角色", "content": "@cache:edit_xxx"}
```

---

## 数据读取速查

### World（世界数据）

```
逐级下钻：
GET /api/roots                                    → 世界列表
GET /api/world/{id}                               → 概览 + 节点链
GET /api/world/{id}/nodes/{nid}                   → 检查点列表
GET /api/world/{id}/nodes/{nid}/{cpId}?truncate=200 → 元素截断预览
GET /api/world/{id}/nodes/{nid}/{cpId}/{key}       → 单个元素全文

一步到位（推荐）：
GET /api/ref?ref=@world:n0002:characters:曹操
GET /api/search?q=曹操&scope=world
```

### Doc（文档/技能/角色/子目录）

```
GET /api/docs                          → 列出所有
GET /api/docs/search?q=关键词          → 关键词搜索
GET /api/docs/{docId}                  → 分页读取
GET /api/docs/{sub/dir/docId}          → 子目录读取
GET /api/ref?ref=@doc:sub/dir/docId    → 一步读取

# 创建时自动路由到 World（省去两步操作）
POST /api/docs
Content-Type: application/json
X-GSim-World-Ref: @world:arknights:n0000:characters:新角色

{"docId": "sub/victoria/duke", "content": "..."}
→ 自动在指定 World checkpoint 创建 route_to_doc 元素
```

### Cache（编辑中间文本）

```
GET /api/caches                          → 列出
GET /api/caches/{id}                     → 读取
GET /api/caches/{id}?offset=10&limit=50  → 分页读取
POST /api/caches/{id}/edit               → 编辑
```

### Agent 生命周期（运行与管理）

**Agent = 配置 + 缓存 + 提示词**，三者组合即可运行一个 Agent：

```
配置（系统提示词、模型参数）
  └── POST /api/agent-configs          创建 Agent 配置
缓存（对话历史、自动注入系统提示词）
  └── POST /api/agent-caches           创建对话缓存
运行（异步启动，通过 SSE 或轮询获取结果）
  └── POST /api/agents/run             启动 Agent → 返回 instanceId
  └── GET /api/agents/{id}/events      SSE 流式事件
  └── GET /api/agents/{id}/output      获取最终文本
```

**Agent 配置管理**：

```
GET    /api/agent-configs                   列出所有配置（摘要）
GET    /api/agent-configs/{configId}         获取配置详情
POST   /api/agent-configs                   创建新配置
PATCH  /api/agent-configs/{configId}        更新配置字段
DELETE /api/agent-configs/{configId}        删除配置
```

**POST /api/agent-configs — 创建 Agent 配置**：

```json
{
  "agentId": "my_custom_agent",
  "llmProvider": "base",
  "temperature": 0.3,
  "maxTokens": 2048,
  "maxToolRounds": 8,
  "toolFilterMode": "read_only",
  "staticSystemPrompt": "你是一个专业的历史顾问。回答要简洁（不超过100字），引用史实要有依据。"
}
```

**PATCH /api/agent-configs/{configId} — 更新字段**：

```json
{"field": "staticSystemPrompt", "value": "新的系统提示词..."}
{"field": "temperature", "value": "0.7"}
{"field": "toolFilterMode", "value": "all"}
```

可更新字段：`llmProvider`, `temperature`, `maxTokens`, `maxToolRounds`, `toolFilter`, `staticSystemPrompt`

**Agent 对话缓存管理**：

```
GET    /api/agent-caches                     列出缓存（?worldId=&agentType=）
GET    /api/agent-caches/{cacheId}           读取缓存（?summary=true 仅摘要）
GET    /api/agent-caches/{cacheId}?limit=10  分页读消息
POST   /api/agent-caches                     创建缓存（自动注入系统提示词）
DELETE /api/agent-caches/{cacheId}           删除缓存
```

**POST /api/agent-caches — 创建对话缓存**：

```json
{
  "configId": "my_custom_agent",
  "worldId": "default",
  "nodeId": "n0000"
}
```

创建时自动从 Agent 配置读取 `staticSystemPrompt` 作为首条 system 消息。  
返回 `cacheId` 用于后续 `POST /api/agents/run` 引用。

**Agent 生命周期**：

```
POST   /api/agents/run                       启动 Agent（异步，立即返回）
GET    /api/agents                           列出所有实例（?status=&configId=）
GET    /api/agents/{instanceId}              查询实例状态
GET    /api/agents/{instanceId}/events       SSE 事件流（等待结果）
GET    /api/agents/{instanceId}/output       获取最终输出文本
POST   /api/agents/{instanceId}/cancel       取消运行中的 Agent
```

**POST /api/agents/run — 启动 Agent**：

```json
{
  "configId": "my_custom_agent",
  "cacheId": "my_custom_agent_2026-07-06T10-30-00.json",
  "prompt": "请分析明朝永乐年间的对外政策",
  "parentInstanceId": "orchestrator-5"
}
```

- `configId`（必填）：使用哪个 Agent 配置
- `prompt`（必填）：Agent 要处理的任务指令
- `cacheId`（可选）：已有对话缓存的 ID。不传则自动创建新缓存（含系统提示词）
- `parentInstanceId`（可选）：父 Agent 实例 ID（SubAgent 时使用）

**响应**：

```json
{
  "instanceId": "my_custom_agent-1",
  "configId": "my_custom_agent",
  "sessionId": "agent-my_custom_agent-1",
  "taskId": "task-my_custom_agent-1",
  "cacheId": "my_custom_agent_2026-07-06T10-30-00.json",
  "parentInstanceId": "orchestrator-5",
  "status": "RUNNING",
  "eventsUrl": "/api/agents/my_custom_agent-1/events",
  "outputUrl": "/api/agents/my_custom_agent-1/output"
}
```

每个 Agent 获得**独立的 sessionId + taskId**，SSE 事件流隔离。

**SSE 事件类型**：

| 事件 | 说明 | 关键字段 |
|------|------|---------|
| `agent_started` | Agent 开始执行 | instanceId, configId, parentInstanceId, cacheId |
| `llm_delta` | LLM 流式增量 | content, agentId |
| `llm_reasoning_delta` | LLM 推理增量 | content, agentId |
| `tool_started` | 工具开始执行 | tool, agentId |
| `tool_done` | 工具执行成功 | tool, agentId |
| `tool_error` | 工具执行失败 | tool, error, agentId |
| `agent_result` | Agent 最终结果 | instanceId, finalText（截断2000字）, cacheId |
| `agent_done` | Agent 完成 | instanceId, status |
| `agent_error` | Agent 出错 | instanceId, error |
| `done` | SSE 流结束 | — |

**获取结果的两种方式**：

```
# 方式 1：轮询（简单）
GET /api/agents/{instanceId}          → 检查 status
GET /api/agents/{instanceId}/output   → status=DONE 后取文本

# 方式 2：SSE 流（实时）
GET /api/agents/{instanceId}/events   → 流式接收所有事件
→ 收到 agent_done 后关闭连接
```

**LLM Provider 管理（已有）**：

```
GET  /api/llm                 列出所有 Provider
GET  /api/llm/{id}            查看 Provider 详情
POST /api/llm/{id}            更新 Provider 字段
```

---

## 典型工作流

### 工作流 A：读 → 编辑 → 写回（最省 Token）

```
1. GET /api/ref?ref=@world:n0002:outcomes:summary
   → 内容长，自动带 @cache:id

2. POST /api/caches/{cacheId}/edit
   {"replace_from": "曹操", "replace_to": "曹公",
    "insert_at": 3, "insert_text": "新增段落..."}
   → {"ref": "@cache:edit_new"}

3. POST /api/world/default/nodes/n0003/outcomes/updated
   {"value": "@cache:edit_new"}
```

### 工作流 B：跨源搜索 → 组合信息

```
1. GET /api/search?q=关羽&scope=all
   → [{source:"world", ref:"@world:n0002:characters:关羽"},
       {source:"doc", ref:"@doc:char_guanyu"}]

2. GET /api/ref?ref=@world:n0002:characters:关羽   → 当前状态
3. GET /api/ref?ref=@doc:char_guanyu              → 角色设定
  
4. 组合后通过 cache edit → @cache:merged → write_element
```

### 工作流 C：route_to_doc（数据路由）

```
1. POST /api/world/{id}/nodes/{nid}/characters/赵云
   {"value": "@doc:char_zhaoyun", "type": "route_to_doc"}

2. GET /api/world/{id}/nodes/{nid}/characters/赵云
   → renderedContent 自动注入 Doc 全文
   → 修改 Doc 则所有路由自动更新
```

### 工作流 D：Agent 端到端（配置 → 缓存 → 运行 → 获取结果）

```
1. 创建 Agent 配置
   POST /api/agent-configs
   {"agentId": "historian", "staticSystemPrompt": "你是一位历史学者...",
    "temperature": 0.3, "maxTokens": 2048, "maxToolRounds": 8,
    "toolFilterMode": "read_only", "llmProvider": "base"}

2. 创建对话缓存（自动注入系统提示词）
   POST /api/agent-caches
   {"configId": "historian", "worldId": "default", "nodeId": "n0000"}
   → {"cacheId": "historian_2026-07-06T10-30-00.json"}

3. 启动 Agent（异步）
   POST /api/agents/run
   {"configId": "historian", "prompt": "分析宋朝海上贸易的三个主要特点",
    "cacheId": "historian_2026-07-06T10-30-00.json"}
   → {"instanceId": "historian-1", "status": "RUNNING",
      "eventsUrl": "/api/agents/historian-1/events"}

4. 等待完成并获取结果
   GET /api/agents/historian-1                      → status: DONE
   GET /api/agents/historian-1/output               → finalText: "宋朝海上贸易..."
   
   或通过 SSE 流式接收：
   curl -N http://127.0.0.1:8710/api/agents/historian-1/events
   → event: agent_started
   → event: llm_delta (重复)
   → event: agent_result
   → event: agent_done
   → event: done

5. 查看完整对话历史（可选）
   GET /api/agent-caches/historian_2026-07-06T10-30-00.json?limit=20
   → messages: [system, user, assistant] 完整对话链

6. 续接对话（再问一个问题）
   POST /api/agents/run
   {"configId": "historian",
    "prompt": "其中哪些特点影响到了明朝的海禁政策？",
    "cacheId": "historian_2026-07-06T10-30-00.json"}
   → 自动加载前轮对话上下文，LLM 有记忆
```

---

## 紧凑输出模式（?format=text）

读操作支持 `?format=text` 参数，直接返回纯文本/Markdown，无需解析 JSON：

```
# 标准 JSON（默认）
GET /api/ref?ref=@world:n0002:characters:曹操
→ {"success":true, "data":{"content":"曹操，字孟德..."}}

# 紧凑文本（?format=text）
GET /api/ref?ref=@world:n0002:characters:曹操&format=text
→ # 曹操 @n0002 (turn 0)
→ 曹操，字孟德，沛国谯县人...

# 支持 ?format=text 的端点
GET /api/ref?ref=...&format=text              → 标题 + 内容 Markdown
GET /api/world/{id}/nodes/{nid}/{cpId}/{key}?format=text  → 元素 raw value
GET /api/docs/{docId}?format=text             → 文档 raw content
```

`?format=compact` 与 `?format=text` 等效。

---

## 省 Token 最佳实践

| 做法 | 效果 |
|------|------|
| 读用 GET /api/ref 不用逐级下钻 | 一次请求，全文到手 |
| 查用 GET /api/search 不用穷举 | 精准定位，不翻页 |
| 编辑走 cache 管道 | 不复制全文，只传操作指令 |
| 写入填 @cache:id | body 只有几十字节，自动展开 |
| 大文本先存为 Doc | 用 route_to_doc 一次引用多处复用 |
| checkpoint ?truncate=200 | 预览阶段不拉全文 |
| 读操作加 `?format=text` | 省去 JSON 解析，直接拿文本 |


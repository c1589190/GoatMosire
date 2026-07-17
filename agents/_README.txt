Agent 配置目录
==============

每个 .json 文件定义一个 Agent。字段说明：

  agentId              — 唯一标识（如 "orchestrator", "sim", "search"）
  llmProvider          — 引用的 LLM provider ID（对应 data/llms.json 中的 id）
  staticSystemPrompt   — 静态系统提示词，直接定义 Agent 行为（可选）
  systemPrompt         — 兼容旧字段（staticSystemPrompt 为空时使用）
  userTemplate         — 用户 prompt 模板路径或文本（可选）
  toolFilter           — { "mode": "all" | "read_only" | "custom", "allow": [...], "deny": [...] }
  maxToolRounds        — 最大工具调用轮数
  temperature          — LLM 温度参数
  maxTokens            — LLM 最大输出 token

内置 Agent 类型：
  orchestrator — 主控 Agent，可管理 SubAgent
  sim          — 推演 SubAgent（只读工具）
  search       — 搜索 SubAgent（只读工具）

你可以添加自定义 Agent（只需新建 .json 文件）。

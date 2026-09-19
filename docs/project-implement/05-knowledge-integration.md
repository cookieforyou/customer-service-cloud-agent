# 05 · 知识服务集成（RAG / FAQ）

> 版本 v1.0.0 ｜ 2026-09-20 ｜ 初版。依赖《01》D-05/D-12。集成事实源：`corporate-knowledge-base-rag-agent`（已上线）仓库实测提取（2026-09-20）。

## 1. 集成契约（事实，非设计）

知识服务（下称 KB 服务）已同时暴露 MCP 与 A2A 两种协议，均为 **Casdoor JWT Bearer** 鉴权（`owner` claim→tenantId、`sub`→userId，fail-closed 三层身份校验；scope 默认不强制）。生产端点 `http://<kb-host>:8090`（dev 8080）。

### 1.1 MCP（Streamable HTTP，单端点 `POST /mcp`）

| 工具 | 参数 | 返回 | 建议超时 | 用途 |
|---|---|---|---|---|
| `search(query)` | 查询串 | `SearchHitView[]`：chunkId/fileName/headingPath/pageNum/content/rerankScore/finalRank（混合检索：改写→向量+BM25→RRF→重排，不经 LLM） | 30s | **主通道**：agentic 检索，片段由本平台 Agent 组织作答 |
| `ask(question)` | 问题 | string（含 `[ref-N]` 引用锚点；内部走完整 RAG 链，每次独立会话无多轮记忆） | 120s | 直答模式：低成本一问一答，无需本平台组织语言时 |
| `get_document(documentId)` | 文档 ID | `DocumentView`：文档元数据+分块全文（租户 fail-closed，软删过滤，上限 50 块） | 30s | 坐席 copilot 深挖 / 追问展开 |

MCP 侧治理（KB 服务侧既有）：独立限流桶 `rag:ratelimit:mcp:{tenantId}`（默认 120 次/60s，fail-open）；错误码 `IDENTITY_INCOMPLETE / MCP_SCOPE_DENIED / RATE_LIMITED / MCP_DOC_NOT_FOUND / PROMPT_INJECTION / TOKEN_BUDGET_EXCEEDED`。

### 1.2 A2A（v1.0 JSON-RPC，`POST /a2a`）

- AgentCard：`GET /.well-known/agent-card.json`（**JWT 保护**，不匿名公开）；skill `kb_qa`；`capabilities{streaming:false, pushNotifications:false}`。
- 仅 `SendMessage` 同步方法；请求须带 `A2A-Version: 1.0` 头（缺失→`-32009`）；多轮延续用同一 `contextId`（服务端会话记忆域 TTL 24h）；结果 Task 终态 `TASK_STATE_COMPLETED`，答案在 `artifacts[0].parts[0].text`（含 `[ref-N]`）。非幂等（messageId 不去重）。

## 2. 通道决策：MCP 为主，A2A 为备

| 维度 | MCP（主） | A2A（备） |
|---|---|---|
| 语义 | Agent→工具（检索是工具调用） | Agent↔Agent（把 KB 当独立对话 Agent） |
| 可控性 | top-k/片段选择/引用组织由本平台 Agent 决定（agentic retrieval，2025-2026 共识模式） | 检索与生成参数收口在 KB 侧 |
| 组合性 | 与 FAQ/业务工具同一工具面统一路由与治理 | 独立任务式调用 |
| 适用 | 知识 Agent 主链路、copilot 实时推荐 | KB 侧 agentic 复用其护栏/记忆的完整链；跨系统互操作演示；MCP 通道故障降级 |

接入形态：`spring-ai-starter-mcp-client-webflux` + streamable-http 连接（`endpoint /mcp`）；**JWT 注入方式为待核验点 V-01**（MCP client 自定义 Authorization 头的配置形态，落码前源码核验；不通过则包一层网关注头或自研轻 MCP client）。

## 3. KnowledgePort 抽象（cs-knowledge/api）

```java
public interface KnowledgePort {
  List<KnowledgeChunk> search(SearchCmd cmd);      // → MCP search；cmd: query, tenantId, topK(默认8)
  KbAnswer ask(KbQuestion q);                       // → MCP ask；返回 answer + refs 解析
  KbDocument getDocument(String docId, Ctx ctx);    // → MCP get_document
  KbAnswer askViaA2a(KbQuestion q);                 // → A2A SendMessage（备用通道，contextId 续轮）
}
```

- 租户上下文经 `ToolContext`（模型不可见）注入 JWT claims，**绝不让模型决定租户**。
- 引用呈现：`[ref-N]` 锚点在本平台侧映射为 chunk 元数据（fileName/pageNum），SSE `TRACE` 帧透出（对齐知识服务溯源形态，便于统一排障习惯）。

## 4. 韧性：缓存、熔断、降级矩阵

| 故障 | 检测 | 行为 |
|---|---|---|
| KB MCP 慢（>阈值） | 超时（search 30s / ask 120s） | 当轮放弃检索 → supervisor 致歉+建议转人工；`cs_kb_timeout_total` 计数 |
| KB MCP 连续失败 | Resilience4j 熔断（失败率 ≥50% 采样 20 次，半开 60s） | 熔断期内：FAQ 本地直答仍可用；知识类问题直接走「降级话术 + 转人工引导」；**自动尝试 A2A 通道**（不同故障域）再降级 |
| KB 服务整体不可用 | 健康探测（MCP initialize 级 ping，30s 周期） | 平台只读服务（FAQ/闲聊/工单）继续，知识类入口隐藏；告警 P2 |
| 限流（RATE_LIMITED） | 错误码识别 | 本平台侧令牌桶预扣（按 KB 120/60s×租户 的 80% 自限），超限走缓存/降级，不硬撞 |
| 返回空召回 | search 空数组 | 触发澄清一次；仍空 → 缺口采集（§6）+ 诚实话术（禁编造，对齐幻觉风险 LLM09） |

语义缓存：《04》§7（同租户同意图桶）；FAQ 更新与 KB 文档变更（若 KB 提供 webhook，M3 对接）触发失效，初期 TTL 自然过期。

## 5. 知识分层与口径

| 层 | 载体 | 命中路径 | 运营职责 |
|---|---|---|---|
| FAQ（问答对） | 本平台 `cs_faq` + Milvus `cs_faq` | 精确 hash → 语义（≥0.92 直出，0.85~0.92 需 Agent 确认问法） | 本平台 admin 维护 |
| 文档知识 | KB 服务（向量+BM25+重排） | `kb_search`/`kb_ask` | KB 平台维护（既有 admin） |
| 结构化业务数据 | 业务系统工具（订单/物流） | transaction_agent 工具调用 | 业务系统侧 |
| 坐席话术库 | 本平台 `cs_faq`（type=SOP） | copilot 检索 | 客服运营侧 |

原则：FAQ 回答口径与文档知识冲突时，以更新时间新者为准并在 admin 冲突检视队列提示（M3 能力，初期人工比对）。

## 6. 知识缺口闭环

```
信号源：转人工会话 / 低置信路由 / search 空召回 / 在线 judge 低分 / 用户点「未解决」
  → KnowledgeGapDetected 事件（question 原文 + 归因初判）
  → ES cs_gap_candidate 聚集（同义问题去重，M3 用 Neo4j 社区聚类增强）
  → admin 缺口队列（人工审核：AI 挖掘误报率行业约 40%，必须人审）
  → 出口A 建/改 FAQ（本平台）｜出口B 推送 KB 平台 badcase 流程（其 admin 已有闭环）
  → 回流验证：缺口问题进入 golden set，下一轮回归必须通过
```

## 7. 修订注记

- v1.0.0（2026-09-20）：初版。

# 06 · 工具中心与 MCP 治理

> 版本 v1.0.0 ｜ 2026-09-20 ｜ 初版。依赖《01》D-05/D-16/D-17，《04》Agent 白名单。

## 1. 工具注册表（cs-tooling）

| 表 | 关键字段 | 说明 |
|---|---|---|
| `cs_tool_def` | tool_name, display_name, risk_level(L0/L1/L2), source(INTERNAL/MCP/A2A), mcp_server_id?, schema(jsonb), quota_config(jsonb), enabled, version | 工具主档；`tool_name` 全局唯一，跨 Agent 复用 |
| `cs_mcp_server` | id, name, endpoint_url, auth_type(JWT/API_KEY), scopes, timeout_ms, rate_limit, health_status | 外部 MCP server 接入档案（首条记录 = KB 服务 `/mcp`） |
| `cs_tool_invocation` | id, trace_id, session_id, turn_id, tool_name, risk_level, args_hash, args_redacted(jsonb), status, latency_ms, approval_id?, created_at | 每次调用留痕（审计数据源之一） |

工具定义三来源：
1. **INTERNAL**：平台内 `@Tool` 方法（Mock 业务工具起步：`order_query`/`ticket_create`/`refund_request`，M2 起逐步替换为真实系统适配）。
2. **MCP**：经 `spring-ai-starter-mcp-client-webflux` 接入的外部 server 工具（KB 服务首例）；MCP 工具经 `SyncMcpToolCallbackProvider` 进入统一 ToolCallback 面，再按 `cs_tool_def` 登记治理属性。
3. **A2A**：远端 Agent 包装为工具（《04》§6.2）。

## 2. 风险分级与处置

| 级别 | 定义 | 处置 | 示例 |
|---|---|---|---|
| **L0 只读** | 无副作用查询 | 自主执行；全量审计 | `kb_search`、`order_query`、`faq_search` |
| **L1 低危写** | 可逆/低影响写操作 | 自主执行 + 全量审计 + 抽样复盘（5%/周） | `ticket_create`、地址修改、留言登记 |
| **L2 高危写** | 资金/权益/外发类 | **100% 人工审批**（坐席审批卡片，完整参数+金额展示）；per-session ≤ 2 次、per-tenant 日配额、单笔金额阈值；审批记录与工具调用同 trace_id | `refund_request`、改价、外呼、发券 |

L2 执行流（与《07》§6 联动）：

```
transaction_agent 产出参数 → Evaluator-Optimizer 自检（schema+业务规则，≤2 次回炉）
  → 创建 cs_approval(PENDING) + TOOL 帧（approvalRequired）推坐席审批卡片
  → 坐席 APPROVE/REJECT（WebSocket）→ ApprovalDecided 事件
  → APPROVE：执行工具（带 approval_id 写入 invocation）→ 结果回 Agent 汇总
  → REJECT/超时(10min)：向用户话术兜底，会话不中断
```

审批必须「批的是完整 payload」（工具名+全部参数+金额+目标对象），坐席台禁止只显示摘要就放行。

## 3. MCP client 接入规范

| 项 | 规范 |
|---|---|
| 传输 | Streamable HTTP（`spring.ai.mcp.client.streamable-http.connections.<name>.url/endpoint`） |
| 鉴权 | JWT（Casdoor，服务身份 client_credentials；租户上下文按调用的会话租户传递）；注入形态待 V-01 核验 |
| 超时 | `request-timeout` 默认 20s 全局；KB 工具按《05》§1.1 覆盖（search/get_document 30s、ask 120s） |
| 限流 | 本平台预扣 KB 侧限流的 80%（§自限令牌桶）；其他 MCP server 按 `cs_mcp_server.rate_limit` 独立令牌桶 |
| 健康 | MCP initialize 级探测 30s 周期 → `cs_mcp_server.health_status`，熔断联动《05》§4 |
| 工具入册 | 新工具必须先登记 `cs_tool_def`（含风险分级）方可被任何 Agent 引用；未登记的 MCP 工具在 ToolCallbackProvider 层过滤掉 |

## 4. 工具可见性（三层求交）

```
runtime_tools(agent, tenant, user) =
    AgentDefinition.tools                // Agent 白名单
  ∩ cs_tool_def(tenant 启用)             // 租户启用集
  ∩ user 权限（坐席/用户角色映射，L2 额外要求审批）
```

- 求交结果注入该轮 ChatClient 的 tools（`ToolCallbackProvider` 过滤实现），**模型永远看不到被裁剪的工具**（对齐渐进披露与最小权限）。
- 工具规模 ≥10 或定义总量 >10K token 时启用 `ToolSearchToolCallingAdvisor`（官方基准省 34–64% token；RegexToolIndex 起步，语义索引用 Milvus `cs_tool_index` 预留）。
- 工具循环上限沿用 Spring AI 默认（`maxCallsPerTool=40 / maxTotalToolCalls=150`，键名以 V-02 核验为准），L2 审批等待不计入循环（挂起态）。

## 5. 工具执行纪律

1. **参数即不可信输入**：LLM 产出的工具参数一律经 JSON Schema 校验（结构化输出 + 校验重试）后才进入执行器；校验失败回炉（≤2 次）后向用户澄清。
2. **结果隔离**：工具返回内容按 `<untrusted_source id="tool:{name}">` 包裹进上下文（spotlighting，防间接注入经工具结果回流，见《11》§3）。
3. **异常语义**：工具异常 message 回传模型供自纠（Spring AI 默认 `ToolExecutionExceptionProcessor`），但 L2 工具异常只回「执行失败」不回内部细节。
4. **幂等**：L1/L2 写工具要求实现方提供幂等键（`invocation_id`）；Mock 工具以 PG 落库模拟。
5. **审计**：`ToolInvocationRecorded` 事件 → `cs_tool_invocation` 落库（append-only）+ `cs_audit_log`（与审批、送审同链，见《10》§6）。

## 6. Mock 业务工具集（M1~M2）

对齐「初期无真实业务系统对接」的现实，先以 Mock 承载编排/审批/评测全链路，契约即未来真实工具契约：

| 工具 | 级别 | 参数 schema 要点 | Mock 行为 |
|---|---|---|---|
| `order_query` | L0 | orderId \| userPhone | 返回固定订单谱（含多状态：在途/退款中/已完成） |
| `ticket_create` | L1 | type, priority, description, attachments? | 落 `cs_ticket`，返回工单号 |
| `refund_request` | L2 | orderId, reason, amount | 走审批流；金额 >阈值时 Mock 拒绝演示降级话术 |

## 7. 平台自身 MCP server（延后，D-16）

M3 决策项：把 `faq_search`、`ticket_create` 等平台工具以 `@McpTool` 暴露（`spring-ai-starter-mcp-server-webmvc`，**显式 `protocol: STREAMABLE`**——默认 SSE 是已验证的坑）。前置条件：外部消费方明确 + `cs_tool_def` 治理属性完备。

## 8. 修订注记

- v1.0.0（2026-09-20）：初版。

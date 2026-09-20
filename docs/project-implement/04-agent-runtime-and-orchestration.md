# 04 · Agent 运行时与编排引擎

> 版本 v1.0.0 ｜ 2026-09-20 ｜ 初版。依赖《01》D-03/D-04/D-06，《03》Advisor 链与上下文。

## 1. Agent 抽象与注册表

```java
// cs-orchestration/api：Agent 即配置，非代码继承
public record AgentDefinition(
    String code,                 // main_supervisor / knowledge_agent / transaction_agent / ...
    AgentType type,              // SUPERVISOR | SPECIALIST | UTILITY(异步：摘要/质检/缺口聚类)
    String systemPromptKey,      // PromptRepository 键（Langfuse 托管，见《09》§5）
    Set<String> tools,           // 工具白名单（与《06》注册表求交）
    ModelTier modelTier,         // T0/T1/T2/T3（§4）
    Duration timeout,            // 单 Agent 执行预算
    boolean streams,             // 是否面向用户流式（仅 SUPERVISOR 为 true）
    boolean hitlCapable,         // 可否发起澄清（AskUserQuestion）与转人工
    int version) {}
```

- 注册表持久化 `cs_agent_def` / `cs_agent_version`（版本化：启用版本 + 历史版本可回滚；修改即新版本，评测绑定版本号）。
- SUPERVISOR 面向用户唯一入口；SPECIALIST 上下文隔离、不直接面向用户流式；UTILITY 为异步旁路（不阻塞主链路）。
- 初始 Agent 集：

| code | type | 职责 | 工具 | tier |
|---|---|---|---|---|
| `main_supervisor` | SUPERVISOR | 对话入口：路由确认、澄清、安抚、派生、汇总应答 | `ask_user_question`、`escalate_to_human`、few-shot 检索 | T1 |
| `faq_agent` | SPECIALIST | FAQ 精确/语义直答（Milvus `cs_faq`） | `faq_search` | T0 |
| `knowledge_agent` | SPECIALIST | 文档知识问答（经 MCP 调知识服务，见《05》） | `kb_search`、`kb_ask` | T1 |
| `transaction_agent` | SPECIALIST | 业务办理（订单查询/工单创建/退款申请） | `order_query`、`ticket_create`、`refund_request`(L2)… | T1 |
| `summary_agent` | UTILITY | 滚动摘要 / warm handoff 摘要生成 | — | T0 |
| `quality_judge` | UTILITY | 在线质检 judge（异步，见《09》§4） | — | T3（跨家族） |

派生纪律（成本护栏）：单 turn 派生 SPECIALIST ≤ 2 个；多 Agent token 成本 ≈ 普通对话 15 倍（Anthropic 实测），SUPPRESSOR 不默认并行派生，仅知识+交易复合诉求时并行。

## 2. 编排引擎（自研轻量，D-04）

**设计词汇采用 Anthropic《Building Effective Agents》五模式**，以显式代码路径（workflow）优先、自主 Agent 谨慎使用：

| 模式 | CSCA 落点 | 触发 |
|---|---|---|
| Routing | 意图路由（§3）：FAQ→faq_agent；知识→knowledge_agent；业务→transaction_agent；闲聊/安抚→supervisor 自答；**模型分级**（简单改写类走 T0） | 每轮入口 |
| Chain | 澄清(AskUserQuestion)→槽位确认→办理→结果确认（transaction 标准链） | 业务办理 |
| Parallelization | 双知识源并行检索（FAQ+文档）；生成与输出送审天然分段并行；多槽位抽取并行 | 复合诉求 |
| Orchestrator-Workers | 主编排按 turn plan 动态拆解派生（复合工单：「查订单+改地址+开票」拆三个 transaction 子任务） | 多任务单 |
| Evaluator-Optimizer | L2 前置自检：transaction_agent 产出参数 → 校验 Agent（schema+业务规则）不过则回炉（≤2 次） | 高危工具前 |

### 2.1 TurnPlan 执行模型

```
TurnPlan { turnId, sessionId, route, steps[] }
Step { type: ROUTE|CLARIFY|AGENT|TOOL|SUMMARIZE|ESCALATE|FINALIZE,
       agentCode?, toolName?, params?, state: PENDING|RUNNING|DONE|FAILED|SKIPPED,
       resultRef? }        // 结果引用而非内联（黑板模式）
```

- **黑板传递**：Step 产物写入会话级 `ConversationState`（Redis + PG 事件），子 Agent 交接 = 任务简报（brief，≤500 token）进、压缩发现（finding，≤800 token）出；结构化产物（检索片段/工具结果）只存引用，final 汇总时按需展开——规避消息链「传话游戏」损耗。
- **可恢复**：TurnPlan 随 `cs_turn` 持久化；进程崩溃后同 turnId 恢复从首个非 DONE 步骤续跑（LLM 调用本身幂等性弱，恢复粒度 = 步骤级而非 token 级）。
- **超时与降级**：单步超时按 AgentDefinition.timeout；SUPERVISOR 整轮预算 45s，超限进入 FINALIZE（道歉+转人工建议）。

## 3. 意图路由（两级 + 兜底）

```
L1 规则层（<5ms）：FAQ 精确命中（归一化问题 hash）/ 渠道快捷入口 / 黑名单
L2 轻量分类器（T0，qwen flash 级）：
   - few-shot 动态检索：Milvus cs_intent_fewshot（标注样本向量检索 top-5 作 few-shot）
   - 输出：intent(label) + confidence，结构化输出（StructuredOutputValidationAdvisor 校验）
L3 LLM 兜底（T1）：confidence < 阈值时由 main_supervisor 判定（把路由当一次工具选择）
```

- 意图集初版：`FAQ / KNOWLEDGE_QA / TRANSACTION / COMPLAINT / CHAT / IRRELEVANT / RISKY`（RISKY→护栏话术，不进生成）。
- 阈值初始 0.7，按评测校准（golden set 上调参）；路由决策连同 evidence 落 `cs_turn.route_decision`（可解释、可回归）。
- **为什么保留分类器**（而非全 LLM 路由）：高 QPS 下成本与延迟；工具/技能规模增大时全 schema 路由准确率塌缩（行业实测 417 工具时降至 20%）；分类器路由属官方客服示例模式（Anthropic Routing）。

## 4. 模型分级路由（D-03）

```java
// cs-ai-core：RoutingChatModel implements ChatModel, StreamingChatModel
// 按 ModelTier + 健康度 + 成本路由到具体 provider 的 ChatModel
enum ModelTier {
  T0_AUX      // 意图/改写/摘要/分类：qwen3.8-flash（百炼 OpenAI 兼容）
  T1_PRIMARY  // 主对话生成：glm-5.3-flash（智谱 OpenAI 兼容）
  T2_FALLBACK // T1 熔断回退：deepseek-v4-flash
  T3_JUDGE    // 质检 judge：跨家族模型（≠生成模型，防自增强偏差）
}
```

- 熔断：连续 5 次失败（5xx/超时）熔断 30s → 自动切 T2；半开探测恢复（对齐知识服务已验证参数）。
- 重试：沿用 `spring-ai-retry` 语义（5xx/IO 指数退避 2s×5 上限 3min、最多 10 次；4xx 不重试），在 RoutingChatModel 内按 provider 定制注入。
- 路由可观测：每次解析记录 `cs_model_route_total{tier,provider,reason}`（reason ∈ {DEFAULT, DEGRADED, COST}）。
- 多租户覆盖：租户级配置可指定 tier→model 映射（合规/成本诉求），默认平台级。

## 5. 流式管线（D-07）

```
Controller(MVC, 虚拟线程) 返回 Flux<ServerSentEvent<Frame>>
  ChatClient.stream() ──→ OutputModerationStreamAdvisor(分段缓冲送审)
                     ──→ PiiOutboundAdvisor
                     ──→ FrameCodec 编码 SSE 帧（《08》§4）──→ 客户端
```

- 分段送审窗口：按句边界或 48 token（先到者切分），已通过前缀透出；审核延迟预算 300ms/段（超时降级为规则敏感词兜底并告警）。
- 首 token 目标 ≤ 2s：路由与检索前置步骤全部并行化（FAQ 精确命中路径 < 300ms 端到端）。
- 帧补发：SSE 断连以 `Last-Event-ID` 重连续传（ring buffer，见《08》§5）。

## 6. A2A 双向互操作（D-06）

### 6.1 Server（把平台 Agent 暴露给企业其他系统）
- 暴露 `main_supervisor` 能力面：AgentCard `GET /.well-known/agent-card.json`（JWT 保护、Cache-Control+ETag），skills：`cs_qa`（客服问答）、`cs_ticket`（工单受理）；端点 `POST /a2a`（A2A v1.0 JSON-RPC，同步 SendMessage；能力位声明 `streaming:false, pushNotifications:false`——与知识服务同水位）。
- 实现路径：M2 spike `spring-ai-community/spring-ai-a2a`（`spring.ai.a2a.server.enabled=true`，AgentCard/AgentExecutor bean 装配）；spike 判据：鉴权可挂 Spring Security、超时可控、无阻塞性缺陷。不通过则按知识服务已验证的自研协议层形态实现（Controller + IdentityGuard + RateLimiter + AuditRecorder 四件套，消息契约对齐其集成指南）。**风险提示：姊妹项目已实证 a2a-java SDK 桥接因 Quarkus/CDI 绑定不适配 Spring 栈（spike 判负，坑#02），spring-ai-a2a 底层同为该 SDK——自研预案为高概率路径，M2 决策点 D-M2-1 见 progress M2 卷。**
- 多轮延续：`contextId` → 映射平台会话（`a2a-{contextId}`，TTL 24h，对齐知识服务语义）。

### 6.2 Client（消费远端 Agent）
- `cs-knowledge` 内置 A2A 客户端调知识服务 `/a2a`（备用通道，见《05》§3）；请求头 `A2A-Version: 1.0`、JWT Bearer；同步等待 ≤ 120s。
- 远端 Agent 亦可注册为 `@Tool`（LLM-driven routing，Spring AI 官方推荐模式），纳入《06》工具治理统一配额。

## 7. 语义缓存（cs-ai-core）

- Redis Stack 向量检索：key 桶 `cs:semcache:{tenant}:{intent}`；embedding 用 T0 族同源模型（与知识服务一致的 1024 维 qwen3.7-text-embedding，便于统一运维）。
- 命中条件：相似度 ≥ 0.95 且同租户且同 agent 版本且 prompt 版本一致；TTL 1h；`FaqUpdated`/知识变更事件主动失效。
- 仅缓存「无副作用轮次」（无工具调用、非澄清轮）；命中直接以缓存答案流式回放并标记 `cache_hit=true`（不进模型计量、进独立统计）。

## 8. 修订注记

- v1.0.0（2026-09-20）：初版。

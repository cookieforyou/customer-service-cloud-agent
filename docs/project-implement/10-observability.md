# 10 · 可观测性

> 版本 v1.0.0 ｜ 2026-09-20 ｜ 初版。依赖《01》D-08，《09》Langfuse 质量运营。

## 1. 总链路

```
Spring AI 2.0 内建 observation (Micrometer)
  → micrometer-tracing-bridge-otel → OTel SDK (OTLP/HTTP)
  → OTel Collector（fan-out）
      ├─→ Jaeger（工程排障：全栈 trace，复用知识服务同款监控形态）
      ├─→ Langfuse 自托管（AI 质量运营：prompt/trace/scores/标注/实验）
      └─→ Prometheus 拉取 metrics（Grafana 工程盘）
```

**semconv 适配器**：OTel GenAI 语义约定截至 2026-09 仍为 Development 状态——导出层单点封装属性名映射（`semconv-adapter` bean），约定升级只改一处（D-08 附带决策）。

## 2. 埋点清单（GenAI span 映射）

| 平台动作 | gen_ai.operation.name | 来源 |
|---|---|---|
| ChatClient 调用 | `chat`（span `chat {model}`） | Spring AI 自动 |
| 意图/few-shot 向量检索 | `retrieval`（`gen_ai.retrieval.top_k`、documents opt-in） | 平台手动（Milvus 检索处） |
| 工具执行 | `execute_tool` | Spring AI 自动（`spring.ai.tool`） |
| FAQ embedding 检索 | `retrieval` | 平台手动 |
| 主编排派生 | `invoke_agent`（agent 名属性） | 平台手动（TurnPlan 步骤边界） |

属性注入：所有 AI span 强制带 `cs.tenant_id / cs.session_id / cs.turn_id / cs.intent / cs.agent_version / cs.prompt_version`；根 span 额外 `langfuse.user.id`（visitor/user）、`langfuse.session.id`（sessionId）——Langfuse 侧按用户/会话归组的前提。

明文策略：`spring.ai.chat.observations.log-prompt/log-completion=true`（排障必需），但**导出前经 PII 脱敏过滤器**（§5）；工具参数/结果默认不打，`include-content` 仅 sit 环境开。

## 3. Langfuse 接入（2026-09-20 环境事实：复用既有实例，不新部署）

- 形态：**复用 kb-rag-agent 已部署的自托管 Langfuse 实例**（同 ECS，ClickHouse/MinIO/PG/Redis 依赖组件均为既有）；CSCA 在实例内**新建独立 Project** 实现数据与权限隔离（独立 pk/sk、独立标注队列/datasets）。容量共享纳入监控；超限时按《14》风险表预案独立加栈。
- 摄入：OTLP/HTTP `POST {langfuse}/api/public/otel/v1/traces`，Basic Auth（pk:sk），头 `x-langfuse-ingestion-version: 4`；**只收 traces 不收 logs/metrics**。
- 已知坑（接入时逐条核验，对应 V-05）：
  1. 仅开 log-prompt 不够：需自定义 `ObservationFilter` 把 `gen_ai.prompt/completion` 写入 high-cardinality key-values，否则 Langfuse 输入输出为 null；
  2. 关闭 Micrometer 与 OTel 双份 HTTP span（`http.server/client.requests` 与 `otel.instrumentation.spring-web*` 二选一）；
  3. `spring-boot-starter-jdbc` 与 incubator 传递依赖冲突需排除；
  4. 无全功能官方 Java SDK——trace 走 OTLP；prompt 拉取/评分回写走 REST。
- 用途分工：trace 树 + 成本/token 统计（generation 维度）、Scores（judge/CSAT 回写）、Annotation Queue（badcase 标注）、Prompt Management（《09》§5）、datasets/experiments。

## 4. 采样与脱敏

| 流量 | 采样率 |
|---|---|
| 默认会话 trace | 20%（head sampling） |
| 含工具调用 / L2 审批 / 转人工 / ERROR | 100% |
| eval 端点 | 100% |

脱敏过滤器位于 OTLP 导出前：PII 七类正则 + 中文 NER（身份证/手机号/银行卡/邮箱/姓名/地址）→ 掩码；原文明文**只**存 PG 审计表（脱敏副本 + hash），Langfuse 侧永不全量明文（隐私边界显式声明：观测系统不存明文 PII）。

## 5. 指标族（Prometheus/Grafana 工程盘）

| 族 | 指标（节选） |
|---|---|
| 会话 | `cs_session_active`、`cs_session_closed_total{reason}`、`cs_turn_total{intent,route}` |
| 时延 | `cs_chat_first_token_seconds`（P95 目标 ≤2s）、`cs_chat_total_seconds`、`cs_kb_search_seconds` |
| 模型 | `cs_model_route_total{tier,provider,reason}`、token in/out（`gen_ai.client.token.usage` 直用）、`cs_model_fallback_total` |
| 知识 | `cs_kb_timeout_total`、`cs_semcache_hit_total / miss_total`、`cs_kb_empty_recall_total` |
| 工具 | `cs_tool_invocation_total{tool,risk,status}`、`cs_tool_approval_total{decision}` |
| 协同 | `cs_escalation_total{trigger}`、`cs_queue_wait_seconds`、`cs_seat_concurrent` |
| 护栏 | `cs_moderation_block_total{layer}`、`cs_injection_detect_total`、`cs_pii_redact_total{type}` |
| 质量 | judge 分数直写 Langfuse（不重复入 Prometheus），闸值告警经 Langfuse Scores API 轮询 |

## 6. 审计日志（合规独立于观测，append-only）

PG 表 `cs_audit_log`（与 Langfuse 物理隔离；观测系统可挂，审计不可挂）：

```json
{
  "event_id":"uuid","ts":"ISO-8601","tenant_id":"...",
  "actor":{"user_id":"...","agent_code":"main_supervisor","agent_seat_id":null},
  "trace_id":"...","session_id":"...","turn_id":"...",
  "event_type":"LLM_CALL|TOOL_CALL|APPROVAL|MODERATION|ESCALATION|DATA_ACCESS",
  "detail":{"model":"glm-5.3-flash","prompt_key":"main","prompt_version":12,
            "tool":"refund_request","args_redacted":{...},"approval_id":"..."},
  "guardrails":{"input_moderation":"pass","output_moderation":"pass","pii":"masked:2"},
  "result":{"status":"success","latency_ms":812,"tokens_total":1533},
  "prev_hash":"sha256","entry_hash":"sha256"
}
```

- 防篡改：`entry_hash = sha256(prev_hash + canonical_json(entry))`，日终校验任务验链（对齐 AI 审计问责制共识：进什么/出什么/系统做了什么/为什么）。
- 留存 180d（可配，≥监管审计预期）；查询经 cs-admin（坐席/合规角色），导出走审批。

## 7. 告警规则（初版阈值）

| 告警 | 条件 | 级别 |
|---|---|---|
| 模型全链路熔断 | T1 与 T2 同时熔断 | P1 |
| KB 服务不可用 | 健康探测失败 2 次 | P2 |
| 首响劣化 | `cs_chat_first_token_seconds` P95 > 4s 持续 10min | P2 |
| 护栏异常 | 送审超时率 >5% 或规则兜底触发率 >1% 持续 15min | P2 |
| 质量劣化 | judge faithfulness 周滚动 < 阈值；某租户低分率突增 >2σ | P2 |
| 审计断链 | hash 校验失败 / 审计写入失败 | P1 |
| 配额将尽 | 租户 TPM 用量 >90% | P3 |

## 8. 修订注记

- v1.0.0（2026-09-20）：初版。

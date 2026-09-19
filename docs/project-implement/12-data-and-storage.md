# 12 · 数据模型与存储选型

> 版本 v1.0.0 ｜ 2026-09-20 ｜ 初版。依赖《01》D-11/D-13。INFRA 现状：ECS 已部署 PG(with vector)/Redis Stack/ES/Milvus/Neo4j。

## 1. 存储分工总表

| 存储 | 角色（唯一职责） | 不做什么 |
|---|---|---|
| **PostgreSQL** | 业务事实主库：会话/消息/事件/坐席/审批/工单/Agent 与工具定义/审计/评测/配额；RLS 兜底 | 不做向量主检索（pgvector 仅备胎） |
| **Redis Stack** | 热态与会话支撑：状态缓存/幂等键/锁/队列(ZSET)/坐席在线/语义缓存(向量)/SSE 补发 buffer/限流桶 | 不做事实源（可丢失可重建） |
| **Elasticsearch** | 全文检索：会话检索（坐席/审计/缺口挖掘）、缺口候选聚集 | 不做主存储、不做向量（向量归 Milvus/语义缓存） |
| **Milvus** | 平台自有向量：FAQ 语义、意图 few-shot、（预留）工具语义索引 | 不承载 KB 文档向量（那归知识服务） |
| **Neo4j** | 图谱（M2+）：缺口问题同义聚类、故障排查树（问题-原因-方案） | 不承载交易/会话数据，只读分析用途 |

## 2. PostgreSQL 模式（Flyway 管理，`V{n}__cs_*.sql`）

### 2.1 表清单（域前缀对应《02》上下文）

| 域 | 表 | 关键索引/约束 |
|---|---|---|
| 租户/身份 | `cs_tenant`、`cs_tenant_config(jsonb 覆盖)`、`cs_user`、`cs_visitor` | |
| 会话 | `cs_session`、`cs_turn`、`cs_message`（月分区）、`cs_session_event`（月分区）、`cs_conversation_summary`、`cs_slot` | `cs_message(tenant_id,channel,channel_msg_id)` 唯一；`(session_id,seq)` 唯一；events `(session_id,seq)` |
| 协同 | `cs_skill_group`、`cs_agent_seat`、`cs_agent_skill`、`cs_escalation`、`cs_approval`、`cs_ticket` | `cs_escalation(session_id)`、`cs_approval(status)` 部分索引 |
| Agent/工具 | `cs_agent_def`、`cs_agent_version`、`cs_tool_def`、`cs_mcp_server`、`cs_tool_invocation` | `cs_tool_invocation(trace_id)`、`(tool_name,status)` |
| 知识 | `cs_faq`（含 type=FAQ/SOP）、`cs_knowledge_gap` | `(tenant_id,enabled)`、问题归一化 hash 唯一 |
| 评测 | `cs_eval_dataset/case/run/result` | run 绑定 agent_version+prompt_label+路由快照 |
| 审计 | `cs_audit_log`（append-only，hash 链，月分区） | `(session_id)`、`(event_type,ts)` |
| 其他 | `cs_moderation_log`、`cs_quota_usage`（小时聚合） | |

### 2.2 关键 DDL 摘要（示例级，落地以迁移脚本为准）

```sql
CREATE TABLE cs_session (
  id UUID PRIMARY KEY,
  tenant_id TEXT NOT NULL,
  channel TEXT NOT NULL,
  visitor_id TEXT,
  user_id TEXT,
  state TEXT NOT NULL,
  intent_current TEXT,
  skill_group_id BIGINT,
  agent_seat_id BIGINT,
  resolution_type TEXT,            -- CONTAINED | VERIFIED | NONE
  csat_score SMALLINT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_active_at TIMESTAMPTZ NOT NULL,
  closed_at TIMESTAMPTZ,
  close_reason TEXT
);
ALTER TABLE cs_session ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cs_session
  USING (tenant_id = current_setting('cs.tenant_id', true));
-- 全部业务表同构 RLS 策略；连接池按请求设置 cs.tenant_id
```

- `cs_session_event.payload jsonb`：`event_type ∈ {MESSAGE_APPENDED, STATE_CHANGED, TOOL_INVOKED, MODERATION, APPROVAL, SUMMARY_UPDATED, SLOT_UPDATED, ROUTE_DECIDED}`——上下文重建与审计回放共用此表。
- 分区：`cs_message/cs_session_event/cs_audit_log` 按 `created_at` 月分区，pg_partman 或原生分区 + 定时任务预建。

## 3. Redis Stack 键设计

| Key | 类型 | TTL | 用途 |
|---|---|---|---|
| `cs:session:state:{sessionId}` | Hash | 25h 滑动 | 会话热态（state/intent/slots 镜像） |
| `cs:idem:{tenant}:{channel}:{msgId}` | String(SETNX) | 30s | 入站幂等前置 |
| `cs:lock:turn:{sessionId}` | Redisson lock | 5s | 单会话单进行轮 |
| `cs:lock:scheduler` | Redisson lock | — | 分配调度 tick 互斥 |
| `cs:queue:{skillGroupId}` | ZSET | 会话期 | 排队（score=优先级分） |
| `cs:seat:{agentSeatId}` | Hash | 心跳续期 | 坐席状态/并发 |
| `cs:sse:buffer:{sessionTurnId}` | List | 5min | SSE 帧补发（≤256） |
| `cs:ratelimit:{scope}:{id}` | 令牌桶(Redisson) | 窗口期 | visitor/tenant/mcp-server 限流 |
| `cs:semcache:{tenant}:{intent}` | Vector(FT) | 1h | 语义缓存（1024 维，cosine，阈值 0.95） |
| `cs:violation:{visitor}` | String counter | 24h | 违规计数（3/5 规则） |

纪律：Redis 只放**可丢失可重建**数据；重建源 = PG 事件流（`cs:session:state` 缓存未命中时从事件重放）。

## 4. Elasticsearch

| 索引 | mapping 要点 | 用途 |
|---|---|---|
| `cs_session_search` | text(ik_max_word/ik_smart) + keyword + date；session 维度聚合消息 | 坐席搜索/审计检索/缺口挖掘（同步侧写：`SessionClosed` 事件异步投递） |
| `cs_gap_candidate` | 问题文本 + 聚类标签 + 计数 | 缺口聚集（同义去重初版用 ES more-like-this，M3 升级 Neo4j 社区发现） |

## 5. Milvus

| Collection | 维度/索引 | 字段 | 用途 |
|---|---|---|---|
| `cs_faq` | 1024 / HNSW COSINE | tenant_id(filter)、type、enabled、faq_id | FAQ 语义直答 |
| `cs_intent_fewshot` | 1024 / HNSW COSINE | tenant_id、intent_label | 意图路由 few-shot 动态检索 |
| `cs_tool_index`（预留） | 同上 | tool_name、tenant_id | ToolSearch 语义索引（启用时建） |

- embedding 模型与知识服务同源（qwen3.7-text-embedding，1024 维），统一运维口径。
- `initialize-schema` 默认 false：集合由 Flyway 后置脚本/启动 Job 幂等建（Milvus 无 Flyway 生态，用自检+创建任务，`cs.infra.milvus.bootstrap=true` 控制）。

## 6. Neo4j（M2+，保守引入）

- 节点：`Question`（缺口候选）、`Faq`、`Symptom/Cause/Solution`（排查树）。
- 用途：缺口同义问题社区发现（Louvain）、排查树遍历辅助 copilot 推荐追问路径。
- 数据来源：仅从 ES/PG 批处理导入（只读分析图），**不进主链路**，故障不影响服务。

## 7. 容量与留存

基线假设（可调）：日均 1 万会话 × 平均 8 轮 → 消息 8 万/日、事件 ~30 万/日、token ~0.5 亿/日。

| 数据 | 增量估算 | 留存 |
|---|---|---|
| cs_message+events | ~1.5GB/月（含 jsonb payload） | 热区 3 个月，冷分区归档 12 个月 |
| cs_audit_log | ~0.8GB/月（脱敏后） | ≥180d 在线（合规），之后对象存储归档 |
| Langfuse(ClickHouse) | trace 20% 采样 ~2GB/月 | 90d（Scores 长期） |
| ES 索引 | ~0.5GB/月 | 6 个月 |
| 语义缓存/热态 | 常驻 <2GB | 自然过期 |

## 8. 修订注记

- v1.0.0（2026-09-20）：初版。

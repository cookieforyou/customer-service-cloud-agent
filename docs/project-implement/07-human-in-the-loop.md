# 07 · 人机协同（HITL）

> 版本 v1.0.0 ｜ 2026-09-20 ｜ 初版。依赖《01》D-14/D-17，《03》会话状态机。

## 1. 转人工触发矩阵

| 信号 | 检测点 | 阈值/规则 | 动作 |
|---|---|---|---|
| 用户显式要求 | 路由层（L1 规则） | 命中「转人工/找客服」词表 | **立即 ESCALATING**，不设重试（行业共识） |
| 负面情绪 | 每轮 T0 情绪分类（与意图路由同模型族） | 连续 2 轮强负面（愤怒/威胁投诉） | 先安抚一轮，仍负面 → ESCALATING |
| 低置信 | 路由/知识置信度 | 低于校准分位（初始 P10，按 golden set 校准） | 澄清 1 次 → 仍低 → ESCALATING |
| 重复未解决 | 会话内意图统计 | 同意图 3 轮未闭环 | ESCALATING |
| 澄清失败 | 澄清管理 | 连续 2 次澄清用户答非所问 | ESCALATING |
| L2 审批 | 工具中心 | 高危工具触发 | **不整会话转人工**：坐席审批卡片（会话保持 AI 态），见《06》§2 |
| 高危/敏感内容 | 护栏 | RISKY 意图 / 连续违规 | 安全话术 + 直接关闭或按租户策略转人工 |

触发统一经 `EscalationRequested` 事件（载荷含 handoffPayload），坐席域与对话主链路解耦。

## 2. 排队与分配

### 2.1 队列模型

```
技能组 cs_skill_group（按意图域划分：售前/售后/退款/投诉/VIP）
  ├─ 优先级：VIP(+20) > 投诉(+10) > 普通(0)；等待时长每 30s +1（防饿死）
  ├─ 坐席并发上限：1~5 可配，默认 4（文字客服行业典型 3~5）
  └─ 溢出策略：本组无可用坐席 → 跨组（技能降级匹配）→ 排队 >SLA → 留言转工单
```

- 队列结构：Redis ZSET `cs:queue:{skillGroupId}`（score=优先级分）；坐席状态 `cs:seat:{agentSeatId}`（ONLINE/BUSY/AWAY + 当前会话数）。
- 分配算法 v1（可解释优先，不上 ML）：**最长空闲 + 技能完全匹配 + 负载最小**加权（0.5/0.3/0.2）；每 2s 调度 tick（分布式锁保护）。

### 2.2 SLA 与超时行为

| 指标 | 目标（初始） | 超时行为 |
|---|---|---|
| 排队首响 | 40s（P80），在线客服优秀基准 | 40s：告知等待+留言选项；120s：自动留言转工单并关闭 |
| 坐席首响 | 60s | 提醒坐席；超 2 次计入坐席质检 |
| L2 审批 | 10min | 自动 REJECT + 用户兜底话术 |

## 3. Warm Handoff（转人工载荷）

`handoffPayload`（由 `summary_agent` T0 生成 + 结构化槽位拼装）：

```json
{
  "summary": "用户诉求一句话 + 已尝试方案 + 当前卡点 + 情绪状态",
  "slots": {"orderId":"...", "intent":"REFUND", "identityVerified": true},
  "aiTranscriptRef": "session_id + turn 区间（坐席台按需展开，非全量推送）",
  "evidence": [{"type":"KB","ref":"[ref-2]","fileName":"退货政策.pdf","pageNum":3}],
  "pendingApproval": {"approvalId":"...", "tool":"refund_request", "args":{...}}
}
```

用户侧过渡话术固定：「正在为您转接【技能组】，已把您的问题摘要带给客服，无需重复描述。」（用户不复述是行业标准，失败多发于 handoff 环节）。

## 4. 坐席工作台（WebSocket）

- 协议：`/ws/agent-console`（JWT 坐席身份），双向：会话分配推送、消息收发、打字状态、接管/转回、审批决策；心跳 15s。
- 坐席侧会话上下文：左=用户对话流（AI 段折叠可展开，含 TRACE 证据）、右=copilot 面板（§5）、中=输入区（支持「以 AI 草稿编辑后发送」）。
- 坐席接管后：消息 role=AGENT 落库；AI 退位为 copilot（不再直接对用户输出）。

## 5. Copilot（AI 辅助坐席）

| 能力 | 实现 | 时延预算 |
|---|---|---|
| 答案推荐 | 坐席输入停顿 800ms → `kb_search`（T0 改写）→ 推荐卡片（含引用） | 2s 内 |
| 话术推荐 | `cs_faq(type=SOP)` 检索 | <500ms |
| 实时摘要 | 会话进行中滚动更新摘要（复用 summary_agent，节流 5s） | — |
| 工单预填 | 从槽位生成工单草稿（wrap-up 阶段） | — |

口径对齐 Zendesk「assisted resolution」模式：copilot 辅助完成的会话不计入 AI Contained Resolution，单列 `assisted` 口径（《09》§1）。

## 6. L2 审批卡片（与《06》§2 对应）

坐席台审批卡片字段：工具名（中文显示名）、完整参数表（KV 全量）、金额与阈值比对、风险提示（策略规则命中说明）、用户上下文摘要。决策：APPROVE / REJECT（必填原因）/ 转人工深聊（把当前会话直接切 WITH_AGENT）。审批动作落 `cs_approval` + `ApprovalDecided` 事件 + 审计链。

## 7. Wrap-up 与口径回流

坐席结束服务 → WRAP_UP 态 → 强制处置表单（≤2min）：

| 字段 | 值域 | 去向 |
|---|---|---|
| 处置结果 | RESOLVED / UNRESOLVED / UPGRADED | `EscalationResolved.outcome` |
| 归因（未解决时） | 知识缺口 / 权限不足 / 业务限制 / AI 误答 / 其他 | 缺口闭环（`KnowledgeGapDetected`）/ 质检线索 |
| 备注 | 自由文本（脱敏入库） | 审计 |

随后会话 CLOSED，`resolution_type` 终判（《09》§1 规则）；72h 同因回流监测由离线任务执行（不改历史，新增回流事件供指标重算）。

## 8. 数据落位

- PG：`cs_skill_group`、`cs_agent_seat`（坐席）、`cs_agent_skill`、`cs_escalation`（触发/队列/分配/接起全轨迹）、`cs_queue_wait`（可省，并入 escalation）、`cs_approval`、`cs_ticket`。
- Redis：`cs:queue:*`（ZSET）、`cs:seat:*`（Hash）、`cs:presence:*`、调度锁 `cs:lock:scheduler`。

## 9. 修订注记

- v1.0.0（2026-09-20）：初版。

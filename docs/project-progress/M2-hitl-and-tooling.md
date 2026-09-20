# M2 · 人机协同与工具治理（周 7–10）

> 对应设计：《14》§1-M2、《06》工具治理、《07》HITL、《04》§6 A2A、《09》§4-§5 ｜ 状态：⬜ 未启动

## 1. 目标与范围
人机协同全流程（转人工/队列/工作台/copilot/审批）+ 工具中心 L0-L2 治理 + 在线 judge v1 + prompt canary + A2A server 落地。

## 2. 前置依赖（用户侧）
坐席测试账号（Casdoor 坐席角色 ≥3，用于并发分配验证）；KB A2A 端点连通性（备用通道联调）。

## 3. 批次规划（2026-09-20 定案，可经复盘调整）

| 批 | 内容 | 涉及模块 |
|---|---|---|
| 批1 | 坐席域基础：技能组/坐席/队列（ZSET）/分配算法 v1 + WebSocket 工作台骨架 | cs-collaboration（《07》§2/§4） |
| 批2 | 转人工全链：触发矩阵 + warm handoff（summary_agent）+ SLA 超时行为 + ESCALATION 帧 + 会话状态联动 | cs-conversation/collaboration（《07》§1/§3） |
| 批3 | 工具中心：注册表 + MCP 治理属性 + 白名单三层求交 + 配额 + Mock 三工具 + invocation 审计 | cs-tooling（《06》） |
| 批4 | L2 审批 + copilot：审批卡片（完整 payload）+ ApprovalDecided 事件 + copilot 三能力（推荐/话术/摘要）+ 工单预填 | cs-collaboration/tooling（《06》§2、《07》§5/§6） |
| 批5 | 质量运营 v1：在线 judge（5 维度/抽样/Scores 回写）+ 校准流程启动（人工标注 200+）+ prompt canary（Langfuse label + 会话粘性切流） | cs-eval/ai-core（《09》§4/§5） |
| 批6 | A2A：spring-ai-a2a spike（判据见《04》§6.1）→ 落地（SDK 或自研）+ AgentCard/JWT/限流 + 客户端备用通道复验 | cs-orchestration/knowledge（《04》§6） |

## 4. 任务清单

| # | 任务 | 负责模块 | 验收标准 | 完成情况 |
|---|---|---|---|---|
| 2.1 | 队列与分配 v1 | cs-collaboration | 最长空闲+技能+负载加权；并发上限 4 生效；调度锁互斥 | |
| 2.2 | 触发矩阵七信号 | cs-conversation | 每信号独立集成测试；显式请求立即转不重试 | |
| 2.3 | warm handoff 载荷 | cs-collaboration | 摘要+槽位+证据+待审批完整；坐席台无全量推送（按需展开） | |
| 2.4 | 坐席工作台 WS | cs-collaboration/api | 分配推送/收发/打字/心跳；断线重连 | |
| 2.5 | 工具注册表 + 三层求交 | cs-tooling | 未登记 MCP 工具被过滤；模型不可见被裁剪工具 | |
| 2.6 | Mock 三工具 | cs-tooling | 契约即未来真实契约；幂等键 | |
| 2.7 | L2 审批全链 | cs-collaboration/tooling | 100% 人审；审批看完整参数；REJECT/超时兜底话术；配额与金额阈值 | |
| 2.8 | copilot 三能力 | cs-collaboration | 推荐 ≤2s；话术 <500ms；滚动摘要节流 5s | |
| 2.9 | 在线 judge v1 | cs-eval | 5%/100% 抽样；Scores 回写 Langfuse；judge 模型跨家族 | |
| 2.10 | 校准流程 | cs-eval | 人标 200+ 起步；一致率报告产出（≥80% 或列人工阶段） | |
| 2.11 | prompt canary | cs-ai-core | 10% 会话粘性切流；label 秒级回退；版本绑定 trace | |
| 2.12 | A2A server 落地 | cs-orchestration | AgentCard(JWT)/SendMessage/限流；contextId 多轮；spike 结论留档 | |

## 5. 决策点（预置，按需拍板）

### D-M2-1 A2A server 实现形态（对应总 ADR D-06 / V-04）
| 选项 | 形态 | 依据 |
|---|---|---|
| **A（预设推荐）** | 自研协议层（Controller+IdentityGuard+RateLimiter+AuditRecorder，契约对齐 KB 服务集成指南） | 姊妹项目同栈已 spike 判负 a2a-java SDK 桥接（Quarkus/CDI 绑定不适配 Spring），自研形态已上线验证；本平台同为 Spring 栈 |
| B | `spring-ai-community/spring-ai-a2a` 自动装配 | 官方社区背书、装配省力；但孵化中（47★）、鉴权/超时可控性待证 |
> 定案记录：待 M2 批6 首步 spike 后拍板（B 过判据则用 B，败则回落 A——回落路径已具备完整参照）。

## 6. 验收标准（里程碑 DoD）
1. 转人工 E2E（触发→排队→接起→copilot→wrap-up→口径落档）销账；2. L2 审批链销账（含 REJECT 分支）；3. judge 一致率报告产出；4. canary 切流+回退演练；5. A2A 端到端（外部调用方视角）销账；6. 复盘提案入 optimization。

## 7. E2E 与热修记录

| 轮次 | 代号 | 结论 | 跟进 |
|---|---|---|---|
| — | E2E-M2-1 转人工全链（待批2 交付） | 待交付 | — |
| — | E2E-M2-2 L2 审批链（待批4 交付） | 待交付 | — |
| — | E2E-M2-3 A2A 互操作（待批6 交付） | 待交付 | — |

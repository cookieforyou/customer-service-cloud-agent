# M1 · 核心问答闭环（周 3–6）

> 对应设计：《14》§1-M1、《03》上下文、《04》路由与模型、《05》知识集成、《09》评测、《10》观测、《11》护栏 ｜ 状态：⬜ 未启动

## 1. 目标与范围

知识类问题端到端可用：会话状态机与上下文工程、意图路由、FAQ/知识双通道、三层护栏（输入审核/PII/流式输出审核）、语义缓存、审计 v1、golden set+CI 门禁、Langfuse 接入。

## 2. 前置依赖（用户侧）

INF-3 KB 服务联调凭据（MCP 连通性）；INF-4 阿里云内容安全开通；INF-5 Langfuse 栈部署（批6 前就位）。

## 3. 批次规划（2026-09-20 定案，可经复盘调整）

| 批 | 内容 | 涉及模块 |
|---|---|---|
| 批1 | 会话域完整：状态机 + 事件溯源 + 上下文组装（滚动摘要/槽位）+ 超时唤醒 + 并发轮锁 | cs-conversation（《03》） |
| 批2 | 意图路由：L1 规则 + L2 T0 分类器 + Milvus `cs_intent_fewshot` + 阈值初校准 | cs-orchestration/knowledge（《04》§3） |
| 批3 | FAQ 通道：`cs_faq` 表 + Milvus `cs_faq` collection + 种子 FAQ（≥50）+ admin 录入最小 API | cs-knowledge/admin（《05》§5） |
| 批4 | 知识通道：MCP client 接入（JWT，V-01 核验落地）+ `kb_search/kb_ask` 封装 + 熔断/超时/自限令牌桶 + TRACE 帧 + A2A 备用通道 | cs-knowledge/tooling/infrastructure（《05》） |
| 批5 | 护栏链：InputModeration（含注入检测）+ PiiIn/Out + OutputModeration 流式分段送审 + 违规计数处置 | cs-ai-core（《11》§2/§4/§5） |
| 批6 | 观测与质量基线：Langfuse 栈接入（ObservationFilter，V-05 核验）+ 审计 v1（append-only+hash 链）+ golden set ≥200 + promptfoo CI 门禁 + 指标族/告警初版 | cs-eval/ai-core/infra（《09》《10》） |

## 4. 任务清单

| # | 任务 | 负责模块 | 验收标准 | 完成情况 |
|---|---|---|---|---|
| 1.1 | 状态机 + 事件溯源 + 分区表落地 | cs-conversation | 状态迁移全路径集成测试；事件可回放重建上下文 | |
| 1.2 | 三层记忆组装 + 摘要压缩 | cs-conversation | 8K 预算控制生效（超限先压窗口）；摘要轮边界对齐 | |
| 1.3 | 幂等/合并/超时唤醒 | cs-conversation/channel | 重放零重复；200ms 合并窗；IDLE→唤醒带摘要 | |
| 1.4 | 意图两级路由 + few-shot 检索 | cs-orchestration | 路由决策落 `cs_turn.route_decision`；阈值 0.7 初值带回写 | |
| 1.5 | FAQ 精确+语义双命中路径 | cs-knowledge | ≥0.92 直出 / 0.85~0.92 确认问法；命中可回退澄清 | |
| 1.6 | MCP 主通道 + 降级矩阵 | cs-knowledge/tooling | 引用 `[ref-N]`→TRACE 帧可溯；停 KB 演练走降级话术+告警 | |
| 1.7 | 护栏三 Advisor + 流式分段送审 | cs-ai-core | 入/出站送审覆盖率 100%；分段窗口按句/48token；命中截断+兜底 | |
| 1.8 | 语义缓存 | cs-ai-core | 同租户同意图 ≥0.95 命中回放；无副作用轮次才缓存 | |
| 1.9 | Langfuse 接入 + 脱敏导出 | cs-ai-core/infra | trace 带 prompt/completion（脱敏后）；user/session 归组正确 | |
| 1.10 | 审计 v1 + 日终验链任务 | cs-domain | hash 链可校验；LLM_CALL/TOOL_CALL/MODERATION 三类先落 | |
| 1.11 | golden set 200 + promptfoo CI | cs-eval | 门禁 ≥95% 且不劣化；合规题库骨架先挂（拒答 300 题目标 M3 补齐） | |
| 1.12 | 首响压测（sit） | cs-eval(loadtest 前身) | 首 token P95 ≤2s | |

## 5. 决策点

（待产生。预期：L2 分类器模型与 few-shot 规模、语义缓存阈值——先按《04》§7 初值，复盘定案。）

## 6. 验收标准（里程碑 DoD）

1. 知识类问题引用可溯（TRACE 帧）；2. KB 故障演练通过（降级+告警）；3. golden set 门禁在 CI 阻塞违规 PR；4. 首 token P95 ≤2s；5. E2E-M1-1/M1-2 销账；6. 复盘提案入 optimization。

## 7. E2E 与热修记录

| 轮次 | 代号 | 结论 | 跟进 |
|---|---|---|---|
| — | E2E-M1-1 问答主链（待批6 交付） | 待交付 | — |
| — | E2E-M1-2 KB 故障演练（待批4 交付） | 待交付 | — |

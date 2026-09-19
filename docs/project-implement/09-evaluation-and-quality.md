# 09 · 评测体系与质量运营

> 版本 v1.0.0 ｜ 2026-09-20 ｜ 初版。依赖《01》D-09/D-15/D-18，《02》cs-eval 模块。

## 1. 指标口径（全平台唯一定义，防营销口径混用）

| 指标 | 定义 | 采集 |
|---|---|---|
| **Contained Resolution** | AI 独立完成（无人工介入）且会话关闭后 72h 内无同因回流（同 visitor+同意图域再开会话） | 离线回流任务重算，日更 |
| **Verified Resolution** | 会话结束前用户显式确认「已解决」（CSAT 邀评中的确认项） | 实时（CSAT 帧） |
| **Assisted Resolution** | 坐席在 copilot 辅助下完成（AI 未直接应答） | wrap-up 处置标签 |
| Deflection | 本应由人工处理、被 AI 拦截完成的会话 / 全部会话（≠resolution，禁止混报） | 同上 |
| CSAT | 会话级满意度（1~5） | CSAT 帧 |
| 转人工率 / 转人工原因分布 | ESCALATING 会话占比；按《07》§1 触发矩阵分类 | 事件统计 |
| 首响/排队 SLA 达成率 | 《07》§2.2 | 指标 |
| 每 Contained Resolution 成本 | 该会话全链 token 计量 × 单价（按 tier 汇总，含 copilot） | token 计量 |
| 小模型分流率 | T0 承担轮次 / 全部轮次 | `cs_model_route_total` |
| 工具成功率 / L2 审批通过率 | invocation 统计 | cs_tool_invocation |

行业参照（仅作对标不设为基线）：Intercom Fin 平均解决率 76%（2026-03，40M+ 会话口径）；Zendesk 官方拆分 Contained/Verified；第三方实测常见真实自动解决 10–20%。

## 2. 离线评测（golden set 回归）

- 数据集：`cs_eval_case`（初始 **≥200 条**人工标注；来源：上线前内部演练脚本 + 上线后生产 trace 抽取；每条含 input、意图标签、参考要点、必含要素、禁止项、期望工具轨迹）。
- 执行器：cs-eval 模块暴露**评测专用端点** `POST /api/v1/eval/chat`（header 钉定 agent 版本 + prompt label，会话标记 `eval=true` 不入生产统计）。
- promptfoo（Node 24，CI 固定版本）：
  - provider = HTTP（打 eval 端点，`transformResponse` 提取 answer/toolCalls）；
  - 断言：确定性（`contains/regex/is-json`）+ 轨迹（`trajectory:tool-sequence`）+ 模型评分（`llm-rubric/g-eval`，judge 用 T3 跨家族模型）；
  - 门禁：通过率 ≥95% 且较上一基线**不下降**（回归即失败）。
- 触发：prompt/agent 定义/模型路由 变更的 PR 必跑；每周全量夜跑。

## 3. 合规题库门禁（GB/T 45654-2025 对齐，D-18）

| 题库 | 规模（初始） | 门禁 |
|---|---|---|
| 拒答题库（31 类风险覆盖） | ≥300 题 | 拒答率 ≥95% |
| 非拒答题库（正常业务，防误拒） | ≥200 题 | 误拒率 ≤5% |
| 生成内容抽检集 | ≥1000 条 | 合格率 ≥90%（judge+人抽 10%） |

题库以 YAML 维护于 `cs-eval/src/main/resources/compliance/`，同时作为 promptfoo 数据源（一鱼两吃）。上线（及每次大版本）必须全绿。

## 4. 在线评测（judge 抽检）

- 维度（1~3 档粒度，行业结论：低粒度比细分数更稳）：事实一致性（vs 检索证据）、方案正确性、语气合规、安全合规（违禁/PII/越权承诺）、升级判断正确性（该转未转/不该转乱转）。
- 抽样：普通会话 5%；含工具调用/投诉关键词/转人工/L2 100%；judge 结果写 Langfuse Scores + `cs_eval_result`。
- 校准（上线 judge 自动处置前置条件）：人工标注 200~500 条，judge–人一致率 ≥80% 或 Cohen's κ ≥0.6；分歧样本月度回流更新 few-shot；judge 模型 ≠ 生成模型。
- 告警：faithfulness 周滚动均值跌破阈值 / 某业务线低分率突增 / 单维度 1 档占比 >5%。

## 5. Prompt 管理与灰度（D-15）

- 管理：Langfuse Prompt Management（不可变版本 + label：`production`/`canary`/`archived`）；运行时 REST 拉取 + 本地缓存 5min + **代码内兜底模板**（Langfuse 故障不阻塞对话，只告警）。
- 切流：`canary` label 按会话粘性切 10% 会话（新会话进入时按 hash 决定，进行中会话不切换——rainbow 语义）；观察 ≥48h 且指标不劣化 → 全量（改 label）。
- 版本绑定：`cs_agent_version.prompt_label` + trace 上报 prompt 版本，评测与指标均可按版本切片归因。

## 6. Badcase 闭环

```
触发：在线 judge 低分 / CSAT ≤2 / 转人工且 UNRESOLVED / 坐席纠正 AI 草稿 / 用户点未解决
  → 标注队列（Langfuse Annotation Queue，人工归因四分类）
  → A 知识缺口 → KnowledgeGapDetected（《05》§6）
    B prompt 缺陷 → prompt 新版本 + golden set 新增用例 → canary
    C 检索失败 → 参数调优 + Python 侧车复测（Ragas context precision/recall、noise sensitivity）
    D 编排错误 → 引擎/路由迭代 + 回归用例
  → 每个出口必须落到「golden set 新用例 或 缺口队列」之一才可关闭 badcase
```

## 7. Python 侧车（D-09，调优期）

- 形态：FastAPI 包装 Ragas/DeepEval，独立容器 `cs-eval-sidecar`，仅内网，由 cs-eval 经 HTTP 调用；提供 promptfoo 没有的检索质量指标族。
- 边界：不进生产链路、不进 CI 常规门禁（避免双 CI 体系）；RAG 调参窗口期启用。

## 8. 数据落位

- PG：`cs_eval_dataset / cs_eval_case / cs_eval_run / cs_eval_result`（run 绑定 agent 版本、prompt label、模型路由快照——保证可复现归因）。
- Langfuse：datasets/experiments（生产 trace 一键转 dataset）、Scores、Annotation Queue。

## 9. 修订注记

- v1.0.0（2026-09-20）：初版。

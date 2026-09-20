# 14 · 实施路线图

> 最后更新:2026-09-20 · v1.1.0(终审：M4 补备份恢复演练 DR-5；§0 补评测侧车) · v1.0.0(初版) ｜ 依赖全部前置文档；周数为相对工期（按 1~2 名资深 + 1~2 名中级工程师估算）

## 0. INFRA 清单（2026-09-20 环境事实更新：以复用为主，无新增重型组件）

| 项 | 用途 | 时点 | 形态 |
|---|---|---|---|
| Prometheus / Grafana / Jaeger | 工程观测（指标/看板/trace） | M0 | **复用 kb-rag-agent 既有实例**：新增 CSCA 独立 scrape job、独立 dashboard、独立 service name 告警规则 |
| OTel Collector（CSCA 自有轻量容器） | OTLP 接入与 fan-out（Jaeger+Langfuse 双后端，缓冲削峰） | M0 | 新增小容器（compose 资产 M0批5 交付） |
| Langfuse | 质量运营（trace/scores/prompt/标注） | M1 | **复用既有自托管实例**：实例内新建 CSCA 独立 Project（独立 pk/sk，数据隔离）；容量共享监控 |
| Casdoor | 认证 | M0 | 复用既有（新建应用注册，INF-2） |
| PG / Redis Stack / ES / Milvus / Neo4j / MinIO | 存储 | M0起 | 直连复用；MinIO 承载消息附件与备份副本 |
| KB 服务联调凭据（服务身份 + scope） | 知识集成 | M1 | INF-3 |
| 阿里云内容安全（文本审核增强版）开通 | 合规护栏 | M1 | INF-4 |
| Python 评测侧车（cs-eval-sidecar：FastAPI+Ragas/DeepEval） | 调优期深度检索指标（《09》§7） | 按需（M3+） | 新增小容器 |

## 1. 里程碑

### M0 · Walking Skeleton（周 1–2）

**目标**：最小端到端打通，验证全部技术基座选型。
- 交付：Maven 多模块骨架 + ArchUnit 规则生效；PG Flyway 基线（会话三表）；Casdoor JWT 接入；webchat 渠道（REST+SSE 帧 v1）；单 `main_supervisor` Agent（硬编码 prompt）+ T1 模型直连；OTel→Jaeger/指标出数；CI 流水线（build/unit/arch/integration）。
- 验收（DoD）：Widget 发消息→SSE 收 TOKEN/DONE 全链通；trace 在 Jaeger 可见；ArchUnit/测试门禁在 CI 强制；V-01~V-03 核验结论回写本文档修订注记。

### M1 · 核心问答闭环（周 3–6）

- 交付：会话状态机+事件溯源+上下文组装（含摘要）；意图路由 L1+L2；`faq_agent`（Milvus `cs_faq` + 种子 FAQ）；`knowledge_agent`（MCP 主通道 + 熔断降级）；Advisor 链 v1（InputModeration/PiiIn/OutputModeration 流式分段）；语义缓存；审计 v1（append-only+hash 链）；Langfuse 部署+接入（含 ObservationFilter）；golden set 200 + promptfoo CI 门禁；指标族与告警初版。
- 验收：知识类问题经 KB 引用可溯（TRACE 帧）；KB 故障演练（停 KB，验证降级话术+告警）；golden set 门禁在 CI 阻塞违规 PR；首 token P95 ≤2s（sit 压测脚本）。

### M2 · 人机协同与工具治理（周 7–10）

- 交付：坐席域全流程（技能组队列/分配算法/坐席工作台 WebSocket/copilot 三能力）；转人工触发矩阵；warm handoff；工具中心（注册表/L0-L2/配额）；Mock 三工具；L2 审批全链；`transaction_agent` + Chain 模式；在线 judge v1（5 维度+抽样+Scores 回写）+ 校准流程；prompt canary（Langfuse label）；A2A spike→server 落地（二选一路径决策回写《04》§6.1）。
- 验收：转人工 E2E（触发→排队→接起→copilot→wrap-up→口径落档）；L2 审批坐席批完整 payload 才可执行；judge 一致率报告（≥80% 或列入人工标注阶段）；canary 切流回退演练。

### M3 · 平台化与合规门禁（周 11–14）

- 交付：多租户配置覆盖与配额计量；ToolSearchAdvisor（工具 ≥10 时）；知识缺口闭环（信号→ES 聚集→admin 队列→出口回流）；合规题库门禁全绿上线；`cs-admin` 管理后台 API（Agent/工具/FAQ/审计/缺口/看板）；openapi 渠道；Neo4j 缺口聚类（可选）；性能压测（cs-loadtest：常规/峰值/知识故障三场景）。
- 验收：合规模块三指标达标（拒答 ≥95%/误拒 ≤5%/抽检 ≥90%）；压测报告达标（NFR 表）；缺口闭环演示（badcase→FAQ→golden set 回归通过）。

### M4 · 上线加固（周 15–16）

- 交付：四场景故障演练（模型全熔断/KB 不可用/Langfuse 不可用/Redis 故障）；**备份恢复演练 DR-5（PG 全量+WAL 恢复到时间点，《12》§7）**；容量与扩容预案落档；发布与回滚手册；安全渗透 checklist 复测；运行手册（SOP：值班/告警响应/审计导出）。
- 验收：演练记录 + 预案评审通过；正式灰度上线（canary 10%→100%）。

## 2. 风险登记表

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| 模型供应商限流/故障 | 中 | 高 | 双供应商熔断路由（T1/T2 独立故障域，D-03）；语义缓存削峰；配额前置 |
| `spring-ai-a2a` 不成熟（V-04） | 高 | 中 | spike 判据明确；**姊妹项目已实证 a2a-java SDK 桥接 Spring 栈判负（坑#02），自研协议层预案为高概率主路径**（M2 决策点 D-M2-1） |
| Langfuse 栈占用 ECS 资源 | 中 | 中 | 复用既有实例按 Project 隔离（零新增组件）；容量共享纳入监控，超限时独立加栈（compose 形态预案保留）；最坏降级为 OTel→Jaeger 单后端（Scores 走 PG `cs_eval_result`） |
| 单机容量上限 | 中 | 中 | 模块化单体资源占用可控；M3 压测给出垂直扩容与「按模块拆分」触发线（D-02 演进路径） |
| OTel GenAI semconv 变更 | 高 | 低 | semconv 适配器单点收敛（《10》§1） |
| 在线 judge 一致率不达标 | 中 | 中 | 分阶段：先只观测不处置，人标回流校准后再自动化（《09》§4） |
| 合规口径变化（备案新规） | 低 | 中 | D-18 双轨设计；题库/审计/标识模块化，开关化启用 |
| 知识服务限流（120/60s/租户）约束 | 高 | 低 | 平台侧 80% 自限令牌桶 + 语义缓存 + FAQ 分流（第一道闸拦截大部分） |

## 3. 进度跟踪

进度按「索引 + 00 状态行卷 + M0~M4 里程碑卷（批次规划/任务清单/决策点/验收/E2E 热修记录）+ 用户侧待执行项清单」组织，推进纪律（功能批四件套、热修循环、复盘成簇、决策纪律）总纲见 `docs/project-progress/PROJECT-PROGRESS.md` §3。每功能批交付即更新任务行与状态行（AGENTS.md 纪律）。

## 4. 修订注记

- v1.0.0（2026-09-20）：初版。

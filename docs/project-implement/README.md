# CSCA 设计实现文档集 · 总目录

> 平台代号 **CSCA**（Customer-Service Cloud Agent，企业智能客服助手 & 多 Agent 协同服务平台）
> 文档集版本：**v1.0.0** ｜ 基准日期：2026-09-20 ｜ 技术基座：Spring AI 2.0.x + Spring Boot 4.1.x + Framework 7 + JDK 25 LTS（已定案）
> 维护纪律：见仓库根 `AGENTS.md`；实证性设计修正回写本目录对应章节，版本号递增 + 修订注记。

---

## 1. 文档地图

| 编号 | 文档 | 主题 | 关键产出 |
|---|---|---|---|
| 01 | [总体架构与关键决策](01-architecture-and-decisions.md) | 产品范围、NFR、总体架构、技术栈、ADR 决策记录 | 架构图、D-01~D-18 决策、待核验清单 |
| 02 | [模块划分与工程结构](02-module-structure.md) | 限界上下文、Maven 多模块、包结构、依赖规则、事件契约 | 模块树、ArchUnit 规则、事件清单 |
| 03 | [会话与上下文工程](03-conversation-and-context.md) | 会话领域模型、状态机、上下文组装、事件溯源、幂等超时 | 状态机、Advisor 链序、表/键设计 |
| 04 | [Agent 运行时与编排引擎](04-agent-runtime-and-orchestration.md) | Agent 抽象与注册、编排模式、意图路由、模型分级、流式管线 | AgentDefinition 契约、RoutingChatModel 设计 |
| 05 | [知识服务集成（RAG/FAQ）](05-knowledge-integration.md) | 既有 RAG Agent 的 MCP/A2A 集成、缓存降级、知识分层、缺口闭环 | 集成契约表、降级矩阵 |
| 06 | [工具中心与 MCP 治理](06-tool-hub-and-mcp-governance.md) | 工具注册表、风险分级 L0/L1/L2、MCP client 接入、配额审计 | 工具治理规则、审批流 |
| 07 | [人机协同（HITL）](07-human-in-the-loop.md) | 转人工触发、技能组队列、坐席工作台、copilot、warm handoff | 触发矩阵、分配算法、SLA |
| 08 | [渠道接入层](08-channel-gateway.md) | ChannelAdapter SPI、Web Widget/OpenAPI、SSE 帧协议、去重限流 | SSE 帧 schema、幂等键设计 |
| 09 | [评测体系与质量运营](09-evaluation-and-quality.md) | 指标口径、golden set、promptfoo CI、在线 judge、prompt canary、badcase 闭环 | 指标定义表、门禁阈值 |
| 10 | [可观测性](10-observability.md) | OTel GenAI 映射、Jaeger+Langfuse 双后端、指标族、审计日志 | 埋点清单、告警规则表 |
| 11 | [安全与合规](11-security-and-compliance.md) | OWASP LLM 2025 映射、分层护栏、PII、内容安全、多租户、GB/T 45654 | 护栏流水线、合规对齐表 |
| 12 | [数据模型与存储选型](12-data-and-storage.md) | PG/Redis/ES/Milvus/Neo4j 分工、核心表 DDL、容量与留存 | 存储分工表、键/索引规范 |
| 13 | [工程规范与质量门禁](13-engineering-standards.md) | 代码规范、测试金字塔、CI/CD 流水线、发布与回滚 | 门禁数值、流水线阶段 |
| 14 | [实施路线图](14-delivery-roadmap.md) | M0~M4 里程碑、交付物与验收、风险登记 | DoD、风险表、新增 infra 清单 |
| 15 | [附录：实证坑位台账](15-附录-坑位台账.md) | 坑位四点联动机制 + 预置继承坑位（姊妹项目）+ 本平台实证坑位 | 台账模板、钩子清单 |

## 2. 阅读顺序（按角色）

- **架构 / Tech Lead**：01 → 02 → 04 → 03 → 12 → 14
- **后端工程师**：02 → 03 → 07 → 08 → 12 → 13
- **AI / Agent 工程师**：04 → 05 → 06 → 09 → 10
- **测试 / 质量工程**：09 → 13 → 11（合规题库部分）
- **运维 / SRE**：10 → 12 → 14（infra 清单与演练）

首次通读建议按 01→14 顺序；后续按角色查阅。所有文档互相引用以 `《NN-文档名》§章节` 形式标注。

## 3. 术语表（全文档统一口径）

| 术语 | 定义 |
|---|---|
| 会话（Session） | 用户与平台的一次完整服务过程，从创建到关闭，含 AI 服务段与人工服务段 |
| 轮次（Turn） | 一次「用户消息 → 平台完整应答（含中间工具/澄清）」的交互单元 |
| 主编排 Agent | 面向用户的唯一对话入口 Agent，负责路由、澄清、派生专职 Agent、汇总应答 |
| 专职 Agent | 被主编排派生、上下文隔离的执行 Agent（知识问答/业务办理等） |
| Contained Resolution | AI 独立完成且会话关闭后 72h 内无同因回流，未经人工 |
| Verified Resolution | 会话结束前用户显式确认「已解决」（口径对标 Zendesk Contained/Verified 拆分） |
| Deflection | 本可由人工处理、被 AI 拦截完成的会话占比 |
| L0/L1/L2 工具 | 只读 / 低危写操作（自主+审计）/ 高危写操作（强制人工审批） |
| 知识服务 | 既有企业知识库 RAG Agent（仓库 `corporate-knowledge-base-rag-agent`，已上线） |
| 护栏（Guardrail） | 在输入→检索→生成→工具→输出链路上拦截风险的可组合组件（Advisor 实现） |

## 4. 外部事实源

| 事实源 | 用途 |
|---|---|
| Spring AI 2.0.1 官方参考文档（docs.spring.io/spring-ai/reference） | API 形态与配置键的权威依据；落码前逐项源码核验 |
| `corporate-knowledge-base-rag-agent` 仓库（已上线） | MCP/A2A 集成契约的事实来源；仅取集成事实，不构成架构决策依据 |
| OTel GenAI 语义约定（open-telemetry/semantic-conventions-genai，Development 状态） | 埋点属性命名 |
| GB/T 45654-2025《生成式人工智能服务安全基本要求》 | 合规题库与门禁量化指标 |
| OWASP Top 10 for LLM Applications 2025 | 威胁模型基线 |

## 5. 修订规范

- 文档头采用**版本链**形式：`> 最后更新:YYYY-MM-DD · vN.M(本次修订摘要) · vN.(M-1)(上一条摘要) · …`（链上保留最近 3 条，更早版本汇入本 README 文档地图的「修订状态」备注）。现有 01~14 头部自 v1.0.0 起步，**首次修订起切换为版本链形式**。
- 每篇文档头部维护 `版本 / 日期 / 修订注记` 三要素；实质性修正递增次版本号（v1.0→v1.1）并写明变更点；实证坑位触发的修订以 `vN.M(坑#xx 续编:…)` 标注（《15》四点联动）。
- 涉及跨文档影响的修改（如 Advisor 链顺序调整），须同步更新引用方文档并在其修订注记标注联动。
- 调研基准日为 2026-09-20；后续技术事实变化（如 Spring AI 2.1、OTel GenAI 转 Stable）触发的设计更新，同样走回写流程。

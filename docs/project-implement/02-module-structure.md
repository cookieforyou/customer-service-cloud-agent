# 02 · 模块划分与工程结构

> 最后更新:2026-09-20 · v1.1.0(终审：事件清单补 `SessionClosed`——ES 会话索引投递的事件源缺口) · v1.0.0(初版) ｜ 依赖《01》D-02（模块化单体）、D-12/D-13

## 1. 限界上下文（Bounded Context）

| 上下文 | 职责 | 聚合根（核心） | 对应 Maven 模块 |
|---|---|---|---|
| 渠道（Channel） | 渠道适配、鉴权入口、幂等去重、SSE 帧协议、限流 | ChannelMessage | cs-channel |
| 会话（Conversation） | 会话生命周期、消息、事件溯源、上下文组装、澄清管理 | Session / Turn | cs-conversation |
| 编排（Orchestration） | Agent 定义与注册、意图路由、编排引擎、A2A 双向 | AgentDefinition / TurnPlan | cs-orchestration |
| 知识（Knowledge） | FAQ 直答、知识服务集成（MCP/A2A）、知识缺口 | FaqEntry / KnowledgeQuery | cs-knowledge |
| 工具（Tooling） | 工具注册表、风险分级、MCP client 治理、审批触发、配额 | ToolDefinition / ToolInvocation | cs-tooling |
| 协同（Collaboration） | 转人工、技能组队列与分配、坐席、copilot、审批处置 | Escalation / AgentSeat | cs-collaboration |
| 质量（Evaluation） | golden set、评测执行、judge 管道、合规题库、指标口径 | EvalCase / EvalRun | cs-eval |
| 管理（Admin） | 管理后台 API 聚合（配置/审计/缺口/看板数据） | —（纯聚合） | cs-admin |
| AI 内核（AI Core） | 模型分级路由、ChatClient 工厂、Advisor 链、Prompt 仓库、语义缓存 | —（能力域） | cs-ai-core |

支撑模块（非业务上下文）：`cs-commons`（跨模块契约）、`cs-infra`（存储与外部系统适配）、`cs-api`（组装与横切配置、启动入口）、`cs-loadtest`（M3 增加）。

## 2. Maven 多模块结构

```
customer-service-cloud-agent (root pom, packaging=pom)
├── cs-commons          纯契约：ApiResponse/错误码/事件契约 DTO/共享注解（不依赖 Spring AI）
├── cs-infra            存储适配（PG/Redis Stack/ES/Milvus/Neo4j 客户端与配置）、外部客户端
│                       （内容安全、Casdoor 资源服务、OTel 装配）
├── cs-ai-core          模型路由(ChatModel 装配/RoutingChatModel)、ChatClient 工厂、
│                       Advisor 实现（护栏/脱敏/记忆桥/观测）、PromptRepository、语义缓存
├── cs-conversation     会话域：实体/仓储/状态机/事件溯源/上下文组装服务
├── cs-knowledge        FAQ（Milvus 检索）+ 知识服务客户端（MCP 主/A2A 备）+ 缺口采集
├── cs-tooling          工具注册表/MCP client 接入/分级治理/配额/审批发起/Mock 业务工具
├── cs-orchestration    Agent 注册表/意图路由/编排引擎/A2A server+client
├── cs-collaboration    转人工决策/技能组队列/分配/坐席工作台服务/copilot/审批处置
├── cs-channel          ChannelAdapter SPI + webchat/openapi 适配器 + SSE 帧编解码
├── cs-eval             评测执行器（CI 可运行 main）/golden set 加载/judge/合规题库 runner
├── cs-admin            管理后台 REST 聚合
└── cs-api              唯一可部署模块（spring-boot-maven-plugin）：启动类、SecurityConfig、
                        OpenAPI 配置、application*.yml 装配、actuator
```

## 3. 包结构与可见性约定

基础包：`com.enterprise.cs`。每个业务模块内：

```
com.enterprise.cs.<module>/
├── api/        对外契约：接口 + DTO + 事件定义（其他模块仅可依赖此包）
├── domain/     实体、仓储接口、领域服务、状态机（模块内部）
├── app/        应用服务（用例编排，事务边界）
└── infra/      模块私有适配（如 SSE 编解码、特定客户端封装）
```

规则（ArchUnit 强制，见 §6）：
1. 跨模块依赖**只能**指向他模块 `..api..` 包与 `cs-commons`。
2. `api` 包内不得出现实体（JPA Entity）与仓储；DTO 用 record，字段命名即契约。
3. `cs-api` 可见所有模块（组装例外）；`cs-admin` 只依赖各模块 api。
4. 禁止循环依赖（模块级与包级双向校验）。

## 4. 模块依赖方向（允许的箭头）

```
cs-channel ──────→ cs-conversation(api) ─→ cs-commons
     │                  │
     │                  ↓
     └──────→ cs-orchestration(api) ─→ cs-knowledge(api) ─→ cs-ai-core(api) ─→ cs-commons
                  │        │                                  ↑
                  │        └──→ cs-tooling(api) ───────────────┘（tooling 用 ai-core 的 ChatClient）
                  └──→ cs-collaboration(api) ─→ cs-conversation(api)
cs-eval ─→ cs-orchestration(api) + cs-knowledge(api)（只读评测视角）
cs-admin ─→ 各模块 api（只读/配置类操作为主）
全部业务模块 ─→ cs-infra（存储/外部客户端装配）   cs-api ─→ 全部（组装）
```

说明：`cs-collaboration` 与 `cs-orchestration` 通过**事件**解耦（转人工由会话事件驱动，而非编排引擎同步调用坐席域），避免核心对话链路被坐席域故障拖垮。

## 5. 模块间通信

### 5.1 同步：api 接口

跨模块同步调用只走 api 包中的接口（如 `cs-knowledge` 暴露 `KnowledgePort.search(SearchCommand)`），实现类在模块内部。超时与降级在**调用方**声明（如编排调知识通道 30s 超时 + 熔断）。

### 5.2 异步：领域事件（Spring Modulith 事件发布注册表）

采用 `spring-modulith-starter-jpa` 的事件注册表（同事务落库、`PUBLISHED→PROCESSING→COMPLETED/FAILED` 生命周期、可重发），监听器统一 `@ApplicationModuleListener`（异步 + 独立事务）。事件契约定义在**发布方** api 包，`cs-commons` 只放公共信封字段（eventId/occurredAt/tenantId）。

### 5.3 核心事件清单（初版）

| 事件 | 发布方 | 消费方 | 载荷要点 | 用途 |
|---|---|---|---|---|
| `SessionStarted` | conversation | evaluation / admin | sessionId, tenantId, channel | 统计 |
| `TurnCompleted` | conversation | collaboration / evaluation | sessionId, turnId, intent, modelTier, tokens, latencyMs, toolCalls | 转人工信号判定 / 成本计量 |
| `EscalationRequested` | conversation | collaboration | sessionId, trigger(枚举), skillHint, handoffPayload(摘要+槽位) | 入队转人工 |
| `EscalationResolved` | collaboration | conversation / evaluation | sessionId, outcome(RESOLVED/UNRESOLVED/UPGRADED), resolutionType(CONTAINED/VERIFIED/NONE) | 会话状态推进 / 口径回流 |
| `ToolInvocationRecorded` | tooling | admin / evaluation | toolName, riskLevel, status, approvalId?, traceId, tenantId | 审计/统计 |
| `ApprovalDecided` | collaboration | tooling | approvalId, decision, approverId | L2 执行放行/拒绝 |
| `KnowledgeGapDetected` | evaluation / conversation | knowledge | sessionId, questionClusterId, evidence | 缺口队列 |
| `FaqUpdated` | knowledge | knowledge(自) / ai-core | faqId, tenantId | 语义缓存失效 |
| `PromptVersionActivated` | admin | ai-core | promptKey, label(production/canary) | 运行时刷新 |
| `ContentPolicyViolated` | ai-core | admin / collaboration | sessionId, layer(INPUT/OUTPUT), action | 违规计数与处置 |
| `SessionClosed` | conversation | knowledge / evaluation / admin | sessionId, closeReason, resolutionType?, csat? | ES 会话索引投递（《12》§4）、口径终判与统计、缺口信号源 |

事件版本化：载荷新增字段向后兼容；破坏性变更新建 `V2` 事件类，旧事件保留一个迁移窗口。

## 6. ArchUnit 架构测试（CI 门禁）

```java
// cs-api/src/test/java/.../ArchitectureTests.java 要点
@AnalyzeClasses(packages = "com.enterprise.cs")
// R1 模块白名单依赖：cs-channel 仅可依赖 conversation/orchestration/commons/infra 的 api
// R2 跨模块只走 api：noClasses().that().resideInAPackage("..cs.<a>..")
//    .should().dependOnClassesThat().resideInAPackage("..cs.<b>.(domain|app|infra)..")
// R3 api 包不出现 JPA：..api.. shouldNot beAnnotatedWith JPA 注解 / 不实现 Repository
// R4 无循环：slices().matching("com.enterprise.cs.(*)..").should().beFreeOfCycles()
// R5 实体不出模块：@Entity 类仅存在于声明模块的 domain 包
// R6 Service 层禁止直接使用 WebClient/RestClient（必须经 infra 适配客户端）
```

违反任何规则 = CI 失败，不允许 `//ArchUnit ignore` 逃逸；确需例外走文档回写并在本文件登记豁免清单（当前为空）。

## 7. 命名与配置约定

| 对象 | 约定 | 示例 |
|---|---|---|
| Maven 模块 | `cs-<context>` | cs-orchestration |
| 表名 | `cs_<snake_case>`，审计/评测等次域用二级前缀 | `cs_session`、`cs_eval_case` |
| Redis key | `cs:<域>:<维度>:<id>` | `cs:session:state:{id}` |
| 配置键 | `cs.<域>.*`（自定义）；Spring AI 原生键不动 | `cs.knowledge.mcp.timeout.search=30s` |
| 指标名 | `cs_<域>_<名词>_<单位>` | `cs_chat_first_token_seconds` |
| 错误码 | 字符串、只增不改（对齐知识服务纪律） | `SESSION_NOT_FOUND` |
| 事件类名 | 名词 + 过去式 | `TurnCompleted` |
| REST 路径 | `/api/v1/<域>/...`；评测专用 `/api/v1/eval/...` | `POST /api/v1/chat/{sessionId}/messages` |

## 8. 构建与版本

- 根 POM `dependencyManagement`：`spring-ai-bom`、`spring-modulith-bom`、Testcontainers BOM；版本集中在根 POM `<properties>`，子模块禁止自声明版本。
- 镜像与运行：`cs-api` 产出 fat jar（`eclipse-temurin:25-jre` 基础镜像）；dev 端口 8081、prod 8090→**本平台使用 8100**（与知识服务 8090 在同一 ECS 共存）。
- Profile：`dev`（本地，连 ECS infra）`sit`（集成）`prod`；`application-ai.yml` 独立维护模型配置（对齐知识服务的配置拆分形态）。
- 版本号：平台 `1.x.y`，文档与代码同版推进（AGENTS.md 纪律）。

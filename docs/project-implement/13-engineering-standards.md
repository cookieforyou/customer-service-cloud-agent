# 13 · 工程规范与质量门禁

> 最后更新:2026-09-20 · v1.1.0(终审：补灰度部署拓扑与优雅停机) · v1.0.0(初版) ｜ 依赖《02》模块结构。总纪律见 `AGENTS.md`（文档 DNA、先核验再落码、一功能一提交等）

## 1. 环境矩阵

| 环境 | 用途 | 数据面 | 备注 |
|---|---|---|---|
| dev | 本地开发 | 连 ECS infra（专用 schema/db 前缀 `cs_dev_`） | Testcontainers 优先，远端仅无法容器化组件 |
| sit | 集成 | 独立 schema + Mock 业务工具 | 自动部署于 main |
| prod | 生产 | 独立库/索引前缀 | 端口 8100（与知识服务 8090 同 ECS 共存） |

## 2. 代码规范

- **JDK 25 idiom**：DTO 一律 record；状态/意图用 sealed interface + enum；switch 模式匹配处理分支；虚拟线程开启（`spring.threads.virtual.enabled=true`，V-03 核验 MVC+Flux 行为）。
- **JSpecify 空安全**：公共 API 显式 `@Nullable/@NonNull`（与 Spring Framework 7 对齐）。
- **分层**：controller（cs-api 与各模块 api 包薄壳）→ app 服务（用例+事务边界）→ domain（实体/仓储/领域服务）→ infra（客户端）。禁止跨层（controller 直调仓储）。
- **错误处理**：业务错误抛 `BusinessException(errorCode)`（`cs-commons`，错误码字符串只增不改）；全局 `GlobalExceptionHandler` 映射 HTTP（对齐知识服务映射惯例：429 配额/409 状态冲突/503 存储不可用/413 载荷/400 业务/500 兜底）。
- **日志**：prod JSON 结构化（logback springProfile 分离，对齐知识服务形态）；INFO 起步；禁打印 prompt 明文与 PII；每条日志带 traceId/tenantId（MDC）。
- **配置**：原生键 `cs.*`，敏感项环境变量注入（.env 模板入库不含值）。

## 3. 测试金字塔

| 层 | 工具与形态 | 范围与规则 |
|---|---|---|
| 单元（≈60%）| JUnit5 + Mockito；**ChatModel mock 惯例**：`mock(ChatModel.class)` 返回构造 ChatResponse（官方无 NoOp 实现，调研已确认） | 领域逻辑/状态机/路由规则/帧编解码；不依赖容器 |
| 切片（≈15%）| `@WebMvcTest`（api 层）/`@DataJpaTest`（仓储+Flyway） | 契约形状 |
| 集成（≈20%）| Testcontainers：PG、Redis Stack、ES、Milvus（`MilvusContainer`+`@ServiceConnection`）、Neo4j | 模块级 `@SpringBootTest`；Mock 业务工具跑通编排全链 |
| 契约 | MCP/A2A 客户端对 WireMock（JSON-RPC 响应快照）；SSE 帧黄金快照 | 上游契约变化即红 |
| 架构 | ArchUnit（《02》§6 六条规则） | 违规=失败，无豁免通道（豁免须回写本文档登记） |
| 评测 | cs-eval + promptfoo（HTTP provider，Node 24） | prompt/agent/路由 变更必跑；门禁见 §5 |
| 红队 | promptfoo red team | 夜间 + 发布前 |

测试数据纪律：golden set 与合规题库同源自 `cs-eval` 资源目录；集成测试不调真实模型供应商（mock ChatModel / eval 端点才出网）。

## 4. CI/CD 流水线

```
PR → build(编译+格式) → unit → arch → integration(Testcontainers)
    → [变更触发] eval(promptfoo golden set) → 报告评论(before/after)
merge(main) → sit 自动部署 → 冒烟(eval 子集)
夜间 → redteam + 全量 eval + 依赖漏洞扫描(SBOM)
发布 → 镜像 tag → 手动 gate（eval 全绿+红队高危0+ArchUnit 0）→ prod canary(10%)
```

- 工具：GitHub Actions（或等价 CI）；promptfoo 固定版本 + 结果缓存复放控成本。
- 提交规范（AGENTS.md）：`feat/fix/docs/refactor(scope): 中文摘要` + 正文要点；一功能一提交（代码+文档同批）；**提交信息带推进锚点**（里程碑批号 `M<N>批<y>` 与坑号 `坑#xx`，与进度卷/坑位台账互查）。
- 分支纪律：默认 main 小步快跑；涉用户 E2E 复跑窗口的大批次开 `m<N>-b<y>-<slug>` 短分支，销账合入（防复跑窗口基线漂移，姊妹项目验证过的形态）。

## 5. 质量门禁数值

| 门禁 | 阈值 |
|---|---|
| 单测覆盖率 | 核心域（conversation/orchestration/tooling/collaboration）≥80%，整体 ≥70% |
| golden set | 通过率 ≥95% 且不劣于基线 |
| 合规题库 | 拒答 ≥95% / 误拒 ≤5% / 抽检合格 ≥90%（上线与发版门禁） |
| 红队 | 高危项 = 0 |
| ArchUnit | 0 违规 |
| 依赖扫描 | 无 critical 未豁免 |

## 6. 发布与回滚

- **Canary（会话粘性）**：新版本接 10% 新会话，进行中会话留在旧版本（rainbow 语义）；观察 ≥30min 指标（错误率/首响/judge 抽检）不劣化 → 50% → 100%。
- **灰度部署拓扑（终审补）**：代码级 canary 需双实例承载——单 ECS 上以 cs-api 双容器（blue/green）+ 前置 nginx upstream 权重分流实现（会话粘性 Cookie 固定版本）；资源受限时退化为「单实例滚动发布（发布窗口=在途轮排空时间）」，灰度以 prompt/配置级 canary（Langfuse label）为主手段。
- **优雅停机（终审补）**：`server.shutdown=graceful`；在途轮排空上限对齐整轮预算（45s+缓冲）；停机前对 SSE/WS 连接广播 DONE/ERROR 帧后再关闭，客户端按重连协议续接。
- **回滚**：镜像回退（≤2min，无状态单部署）；prompt 回退 = Langfuse label 切回（秒级，进行中会话下轮生效）；数据不回滚（事件只追加）。
- **变更窗口**：Agent 定义/prompt 变更走灰度；DDL 变更向后兼容两版（先加列后删列，禁锁表变更走在线迁移）。

## 7. DoD（功能批完成定义）

1. 代码 + 测试（对应金字塔层）+ ArchUnit 绿；
2. 涉及 prompt/agent/路由的变更：eval 门禁绿 + 报告附 PR；
3. 文档回写（设计文档章节版本递增 + 修订注记 + `docs/project-progress/` 任务行）；
4. 指标/告警随功能上线（无裸功能）；
5. 用户侧 E2E 测试步骤交付（AGENTS.md：不启动服务，用户自测回传 `logs/`）。

## 8. 修订注记

- v1.0.0（2026-09-20）：初版。

## 开发纪律（用户定案）

文档是项目的 DNA：**功能实现/修 bug 校验后先更新文档再提交代码**，不可颠倒遗漏：

1. **文档纪律**：设计实现文档前一定要做 Web 调研，分析当前（2026 年 9 月）市场最新主流落地技术，以前沿的视角与企业级的标准来设计实现。落文档时不要把内容揉在一篇里大而全的文档里，要分模块、分章节、分文件按顺序编号输出一系列成体系的高质量文档集合，并配一个总目录入口文档
2. **设计回写**：实证性设计修正回写 `docs/project-implement/` 对应章节（版本号递增 + 修订注记）
3. **进度更新**：`docs/project-progress/` 对应任务行 + 顶部日期状态行
4. **AGENTS.md 同步**：受影响的架构事实（只记架构事实，过程细节入进度文档，控制体积 ≤20KB）
5. **git 提交**：一功能一提交（代码 + 文档同批），提交信息沿用既有风格（`feat/fix/docs/refactor(scope): 中文摘要` + 正文要点）
6. **落码约束**：写代码前源码级核验（API 形态/契约/默认行为），不确定搜索官方文档，先核验再落码
7. **通盘思考优先**：实现前先审视设计合理性与可维护性，有更优方案先与用户定案，再实现并回写设计
8. **E2E 自测形态**：不启动服务；功能批完成即交付测试步骤 + 文档回写 + 提交，用户自测结果下轮回传更新 E2E 记录
9. **临时证据路径纪律**：仓库根 `logs/` = 用户侧回传证据临时区（会清除）——文档不引用其下具体文件路径，证据以读数/结论/现象落档
10. **常量收敛纪律**：Constants 收敛跨类/跨模块共享的协议性字面量

## 架构事实（同步自 docs/project-implement v1.0.0，2026-09-20）

- **平台**：CSCA（企业智能客服 & 多 Agent 协同平台）。技术基座已定案：Spring AI 2.0.x + Spring Boot 4.1.x + Framework 7 + JDK 25 LTS；模型走 OpenAI 兼容（T1 glm-5.3-flash 主 / T2 deepseek 回退 / T0 qwen flash 辅助族，与知识服务同源策略）。
- **形态**：模块化单体——Maven 多模块（cs-commons/infra/ai-core/conversation/knowledge/tooling/orchestration/collaboration/channel/eval/admin/api）+ ArchUnit 边界 + Spring Modulith 事件注册表；基础包 `com.enterprise.cs`；prod 端口 8100（与知识服务 8090 同 ECS 共存）。
- **权威设计**：`docs/project-implement/`（README 总目录 + 01-14）。Advisor 链序唯一权威定义在《03》§4；指标口径（Contained/Verified Resolution 等）唯一权威在《09》§1；ADR 决策记录 D-01~D-18 在《01》§5。
- **关键外部契约**：知识服务（corporate-knowledge-base-rag-agent，已上线）MCP `POST /mcp`（工具 search/get_document/ask，JWT，限流 120/60s/租户）为主通道；A2A `POST /a2a`（v1.0 JSON-RPC + AgentCard，JWT，contextId 多轮）为备用通道。鉴权同源 Casdoor。
- **落码前必核验**：设计文档中标注 V-01~V-06 的 API/配置点（MCP client 鉴权头注入、tools.limits 键名、MVC+Flux SSE、spring-ai-a2a 成熟度、Langfuse ObservationFilter、Milvus 过滤下推），先源码核验再落码（纪律 6 的具体化清单）。
- **新增 infra**（M0/M1 落位）：OTel Collector+Jaeger+Prometheus/Grafana、Langfuse 自托管栈（+ClickHouse+MinIO）。

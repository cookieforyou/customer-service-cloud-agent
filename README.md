# CSCA · 企业智能客服助手 & 多 Agent 协同服务平台

企业级智能客服（多渠道接入 / 知识问答 / 业务办理 / 人机协同）与多 Agent 协同平台（Agent 编排 / 工具与 MCP 治理 / A2A 互操作 / 评测与质量运营）。

**技术基座**（2026-09 定案，见 `AGENTS.md`）：Spring AI 2.0.x · Spring Boot 4.1.x · Spring Framework 7 · JDK 25 LTS · 模块化单体（12 Maven 模块 + ArchUnit 边界 + Spring Modulith 事件注册表）

- 设计文档（唯一依据）：[`docs/project-implement/`](docs/project-implement/README.md)
- 项目进度：[`docs/project-progress/`](docs/project-progress/PROJECT-PROGRESS.md)
- 当前阶段：M0 Walking Skeleton 进行中（M0批1 工程骨架已交付）

## 模块

`cs-commons · cs-infra · cs-ai-core · cs-conversation · cs-knowledge · cs-tooling · cs-orchestration · cs-collaboration · cs-channel · cs-eval · cs-admin · cs-api（唯一部署单元，dev 8081 / prod 8100）`

## 构建

```bash
mvn -B -ntp verify   # 全量：编译 + 单元 + ArchUnit 架构守护 + 集成
```

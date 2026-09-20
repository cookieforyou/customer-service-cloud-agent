# M0 · Walking Skeleton（周 1–2）

> 对应设计：《14》§1-M0、《02》模块结构、《03》会话、《08》渠道、《10》观测 ｜ 状态：⬜ 未启动

## 1. 目标与范围
最小端到端打通（Widget 发消息 → supervisor 直连 T1 生成 → SSE 回流），验证全部技术基座选型与工程骨架；不含护栏/路由/知识集成（M1+）。

## 2. 前置依赖（用户侧）
INF-1 监控栈（OTel Collector+Jaeger+Prometheus/Grafana，见用户侧清单）；INF-2 Casdoor 应用注册。ECS 既有 PG/Redis/ES/Milvus/Neo4j 直接复用（Testcontainers 为主，远端仅冒烟）。

## 3. 批次规划（2026-09-20 定案，可经复盘调整）

| 批 | 内容 | 涉及模块 |
|---|---|---|
| 批1 | 工程骨架：12 模块 Maven 树 + ArchUnit 六规则 + CI 流水线（build/unit/arch）+ 进度卷启用 | 全部（《02》） |
| 批2 | 数据基线：Flyway 基线（cs_session/cs_turn/cs_message + RLS）+ Redis 键规范空实现 + Testcontainers 集成样板 | cs-domain/infra（《12》§2） |
| 批3 | 认证与渠道：Casdoor JWT（访客/坐席/服务三身份）+ webchat REST+SSE 帧 v1 + 幂等去重 + 最小 Widget 页（无构建依赖原生 ESM 起步，正式前端技术栈 M1 复审定案） | cs-channel/api（《08》《11》§7） |
| 批4 | 最小对话链：main_supervisor（代码内 prompt）+ RoutingChatModel 骨架（T1 直连）+ SessionMemoryAdvisor 雏形（窗口读拼） | cs-ai-core/orchestration/conversation（《04》《03》） |
| 批5 | 观测与核验收口：OTel→Jaeger/Prometheus 出数（含根 span 属性）+ V-01~V-03 源码核验结论回写《01》§7 + E2E-M0-1 交付 | 全部（《10》） |

## 4. 任务清单

| # | 任务 | 负责模块 | 验收标准 | 完成情况 |
|---|---|---|---|---|
| 0.1 | Maven 多模块 + 根 POM BOM 钉版 | 全部 | reactor BUILD SUCCESS；子模块无自声明版本 | |
| 0.2 | ArchUnit 六规则（《02》§6） | cs-api(test) | 违规=CI 失败；故意注入违规可红 | |
| 0.3 | CI 流水线四阶段 | — | PR 全链自动执行 | |
| 0.4 | Flyway 基线 + RLS | cs-domain | 迁移可重放；跨租户查询被 RLS 拦截的集成测试 | |
| 0.5 | Casdoor JWT 资源服务 + 三身份 | cs-api | 访客/坐席/服务 token 分别过/拒 | |
| 0.6 | webchat 渠道 + SSE 帧协议 v1 | cs-channel | ACK/TOKEN/DONE/ERROR 帧可收；断线 Last-Event-ID 补发 | |
| 0.7 | 入站幂等去重 + 访客限流 | cs-channel | 同 channel_msg_id 重放只产生一轮 | |
| 0.8 | main_supervisor 最小链路 | cs-orchestration/ai-core | 消息→T1→SSE 全通；turn/message/event 落库 | |
| 0.9 | 观测出数 | cs-api | Jaeger 可见 chat span；Prometheus 抓到 cs_* 指标 | |
| 0.10 | V-01~V-03 核验回写 | — | 《01》§7 状态更新 + 修订注记 | |

## 5. 决策点
（暂无待拍板项；产生即按「选项表+定案记录」格式追加）

## 6. 验收标准（里程碑 DoD）
1. Widget→SSE 全链通，trace/指标可见；2. ArchUnit/测试门禁 CI 强制；3. V-01~V-03 核验结论回写；4. E2E-M0-1 用户回传销账；5. 复盘提案入 project-optimization（M1 批次定案依据）。

## 7. E2E 与热修记录

| 轮次 | 代号 | 结论 | 跟进 |
|---|---|---|---|
| — | E2E-M0-1（交付于批5） | 待交付 | — |

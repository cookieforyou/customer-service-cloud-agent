# M3 · 平台化与合规门禁（周 11–14）

> 对应设计：《14》§1-M3、《09》§3/§6、《11》§8、《05》§6、《06》§4 ｜ 状态：⬜ 未启动

## 1. 目标与范围

多租户配置与配额、工具规模化（ToolSearch）、知识缺口闭环、合规题库门禁全绿、管理后台 API、openapi 渠道、压测三场景。

## 2. 前置依赖（用户侧）

第二个测试租户（多租户隔离验证）；合规题库评审人（拒答/非拒答标注复核）。

## 3. 批次规划（2026-09-20 定案，可经复盘调整）

| 批 | 内容 | 涉及模块 |
|---|---|---|
| 批1 | 多租户：`cs_tenant_config` 覆盖合并 + 配额计量（RPM/TPM/高危次数）+ RLS 全表补齐 | cs-domain/channel（《11》§6） |
| 批2 | 工具规模化：ToolSearchToolCallingAdvisor（Regex 索引）+ 工具语义索引预留 + 工具 ≥10 场景验证 | cs-tooling/ai-core（《06》§4） |
| 批3 | 缺口闭环：信号采集（五源）+ ES `cs_gap_candidate` 聚集 + admin 队列 + 出口回流（FAQ/KB badcase/golden set） | cs-knowledge/eval/admin（《05》§6） |
| 批4 | 合规门禁：题库三件（拒答 ≥300 / 非拒答 ≥200 / 抽检 ≥1000 载具）+ CI 门禁 + AI 标识/投诉入口/违规处置 | cs-eval/channel（《09》§3、《11》§5/§8） |
| 批5 | 管理后台：admin API 聚合（Agent/工具/FAQ/审计查询/缺口/看板数据）+ 审计导出审批流 | cs-admin（《02》） |
| 批6 | openapi 渠道 + 压测：同步 JSON 契约 + cs-loadtest 三场景（常规/峰值/知识故障） | cs-channel/eval（《08》§2、《13》） |

## 4. 任务清单

| # | 任务 | 负责模块 | 验收标准 | 完成情况 |
|---|---|---|---|---|
| 3.1 | 租户配置覆盖 | cs-domain/ai-core | prompt/工具/路由/阈值四类可按租户覆盖；默认合并正确 | |
| 3.2 | 配额计量与限流联动 | cs-channel/domain | TPM>90% 告警；超限新会话拒+进行中降级 T0 | |
| 3.3 | ToolSearch 启用 | cs-tooling | ≥10 工具下 token 节省有实测对比；选择准确率不降 | |
| 3.4 | 缺口五源信号 | cs-conversation/eval | 信号→事件→ES 全通；同义聚集初版可用 | |
| 3.5 | 缺口回流双出口 | cs-knowledge/admin | 建FAQ/推KB badcase 两条路走通；回流用例进 golden set | |
| 3.6 | 题库三件齐备 | cs-eval | 拒答 ≥300（≥95%）/非拒答 ≥200（误拒 ≤5%）/抽检 ≥1000 载具（≥90% judge+人抽10%） | |
| 3.7 | AI 标识与投诉入口 | cs-channel | 页脚+首条标识；openapi 响应 generatedBy；投诉直建工单 | |
| 3.8 | admin API 六域 | cs-admin | 审计/缺口/看板只读聚合；敏感操作二次确认 | |
| 3.9 | openapi 渠道 | cs-channel | 同步应答+turnId+refs；client_credentials 鉴权 | |
| 3.10 | 压测三场景 | cs-loadtest | NFR 表达标（常规/峰值/知识故障降级）报告落 docs/reports/ | |

## 5. 决策点

（待产生。预期：Neo4j 缺口聚类是否本期启用（可选批）；IM 渠道适配范围——按复盘与用户需求定。）

## 6. 验收标准（里程碑 DoD）

1. 合规模块三指标全绿（发版门禁生效）；2. 多租户隔离 E2E（跨租户探针全拒）；3. 缺口闭环演示（badcase→FAQ→golden set 回归通过）；4. 压测报告达标落档；5. 复盘提案入 optimization。

## 7. E2E 与热修记录

| 轮次 | 代号 | 结论 | 跟进 |
|---|---|---|---|
| — | E2E-M3-1 多租户隔离（待批1 交付） | 待交付 | — |
| — | E2E-M3-2 缺口闭环（待批3 交付） | 待交付 | — |
| — | E2E-M3-3 合规门禁（待批4 交付） | 待交付 | — |
| — | LT-1 压测三场景（待批6 交付） | 待交付 | — |

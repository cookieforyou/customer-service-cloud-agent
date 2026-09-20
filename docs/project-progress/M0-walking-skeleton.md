# M0 · Walking Skeleton（周 1–2）

> 对应设计：《14》§1-M0、《02》模块结构、《03》会话、《08》渠道、《10》观测 ｜ 状态：🔶 进行中（M0批1–M0批4 已交付 ✅ 2026-09-20）

## 1. 目标与范围

最小端到端打通（Widget 发消息 → supervisor 直连 T1 生成 → SSE 回流），验证全部技术基座选型与工程骨架；不含护栏/路由/知识集成（M1+）。

## 2. 前置依赖（用户侧）

INF-1 监控栈（OTel Collector+Jaeger+Prometheus/Grafana，见用户侧清单；资产 M0批5 交付）；INF-2 Casdoor 应用注册 ✅ 已销账（2026-09-20 回传核验，坑#19 契约修正，详见用户侧清单）。ECS 既有 PG/Redis/ES/Milvus/Neo4j 直接复用（Testcontainers 为主，远端仅冒烟）。

## 3. 批次规划（2026-09-20 定案，可经复盘调整）

| 批 | 内容 | 涉及模块 |
|---|---|---|
| M0批1 | 工程骨架：12 模块 Maven 树 + ArchUnit 六规则 + CI 流水线（build/unit/arch）+ 进度卷启用 | 全部（《02》） |
| M0批2 | 数据基线：Flyway 基线（cs_session/cs_turn/cs_message + RLS）+ Redis 键规范空实现 + Testcontainers 集成样板 | cs-domain/infra（《12》§2） |
| M0批3 | 认证与渠道：Casdoor JWT（访客/坐席/服务三身份）+ webchat REST+SSE 帧 v1 + 幂等去重 + 最小 Widget 页（无构建依赖原生 ESM 起步，正式前端技术栈 M1 复审定案） | cs-channel/api（《08》《11》§7） |
| M0批4 | 最小对话链：main_supervisor（代码内 prompt）+ RoutingChatModel 骨架（T1 直连）+ SessionMemoryAdvisor 雏形（窗口读拼） | cs-ai-core/orchestration/conversation（《04》《03》） |
| M0批5 | 观测与核验收口：OTel→Jaeger/Prometheus 出数（含根 span 属性）+ V-01~V-03 源码核验结论回写《01》§7 + E2E-M0-1 交付 | 全部（《10》） |

## 4. 任务清单

| # | 任务 | 负责模块 | 验收标准 | 完成情况 |
|---|---|---|---|---|
| 0.1 | Maven 多模块 + 根 POM BOM 钉版 | 全部 | reactor BUILD SUCCESS；子模块无自声明版本 | ✅ 2026-09-20 M0批1：12 模块全绿；钉板 Boot 4.1.0 / Spring AI BOM 2.0.1 / Modulith BOM 2.1.1 / ArchUnit 1.5.0 / Testcontainers BOM 2.0.5（核验：Boot/Spring AI 本机 .m2 实存（姊妹同钉板可复现），Modulith/ArchUnit 官方 releases 最新稳定，读数见 00 卷） |
| 0.2 | ArchUnit 六规则（《02》§6） | cs-api(test) | 违规=CI 失败；故意注入违规可红 | ✅ 2026-09-20 M0批1：六规则落地；实证注入 R2 违规（channel→conversation.domain）被拦（2 violations）后回滚复验全绿；r3/r6 骨架期 `allowEmptyShould(true)`（api/app 包尚无类，护栏随代码生长生效）；另实证 Maven 依赖图为第一道闸（无依赖时编译期即拦） |
| 0.3 | CI 流水线四阶段 | — | PR 全链自动执行 | ✅ 2026-09-20 M0批1：`.github/workflows/ci.yml` 三 job（build→test(unit+arch)→integration），JDK 25 temurin + maven cache；eval/redteam 接入点注释挂 M1批6/M3。注：PR 自动触发依赖远端仓库托管，配置就绪待远端启用 |
| 0.4 | Flyway 基线 + RLS | cs-domain | 迁移可重放；跨租户查询被 RLS 拦截的集成测试 | ✅ 2026-09-20 M0批2：V1（三表+索引+唯一键+RLS ENABLE/FORCE+fail-closed 策略）+ V2（cs_app 执行角色+授权+默认权限）；实体三/仓储三（Hibernate validate 过，jsonb 字段 @JdbcTypeCode(SqlTypes.JSON)）；集成测试 2/2 绿——RLS 四场景实证（跨租户读 0 行/点名 0 行/越租户写拒/无上下文写拒 + 管理连接对照可见）；全装配冒烟含真实 PG 迁移；坑#13（TC 2.x 形态三变化）/坑#14（嵌套 JdbcTemplate 连接不一致）登记《15》。注：Redisson 装配与业务使用随 M0批3（幂等）接入，本批键常量收敛（RedisKeys）。迁移落位/RLS 执行形态回写《12》v1.2.0 |
| 0.5 | Casdoor JWT 资源服务 + 三身份 | cs-api | 访客/坐席/服务 token 分别过/拒 | ✅ 2026-09-20 M0批3：双 issuer 资源服务器（Casdoor + 平台自签访签 `urn:csca:visitor`）；访客=appKey+HMAC 验签换发（scope=cs.webchat→ROLE_VISITOR），坐席=Casdoor roles→ROLE_*（AGENT 无 chat 权 403 实证）；无 token 401 实证。回写《11》§7 v1.2.0（访签平台自签定案） |
| 0.6 | webchat 渠道 + SSE 帧协议 v1 | cs-channel | ACK/TOKEN/DONE/ERROR 帧可收；断线 Last-Event-ID 补发 | ✅ 2026-09-20 M0批3：visitor-tokens/sessions/messages/stream 四端点 + 帧总线（directBestEffort 热流 + 256 内存补发缓冲，Last-Event-ID 头 + query 降级）；全链集成测试实证 TOKEN×N+DONE、序号单调、id>afterId 精确补发；MVC 返回 Flux（V-03 实证可用，心跳挂 M0批5）。偏离注记：缓冲 Redis 化随多实例（M0批5 复审），回写《08》v1.1.0 |
| 0.7 | 入站幂等去重 + 访客限流 | cs-channel | 同 channel_msg_id 重放只产生一轮 | ✅ 2026-09-20 M0批3：双闸实证——Redis SETNX/回执缓存路径 + 清 Redis 后 DB 唯键兜底路径（冲突事务滚出后新事务补偿读，坑#18）均 duplicate=true 且 messageId 一致；限流 RPM=3 实证第 4 次 429 RATE_LIMITED |
| 0.8 | main_supervisor 最小链路 | cs-orchestration/ai-core | 消息→T1→SSE 全通；turn/message/event 落库 | ✅ 2026-09-20 M0批4：RoutingChatModel 骨架（T1 直连 + TierRoutingOptions 解析缝，熔断/T2 挂 M1；坑#20 Spring AI 2.0 装配形态核验）；supervisor 引擎（代码内 prompt + SessionMemoryAdvisor 窗口读拼，组装序 system-first 实证修正）；V3 cs_session_event 落位（ROUTE_DECIDED/MESSAGE_APPENDED）；轮次生命周期落库由 channel 适配器驱动（模块白名单，orchestration 保持纯运行时）。echo 桩经 `CS_TURN_ENGINE=orchestration` 退役切换。`mvn verify` 全绿（37 测试：编排全链集成 3（TOKEN×N+DONE/落库四表断言/窗口历史进第二轮 prompt/故障 FAILED+ERROR 帧）+ 引擎单测 2 + 路由单测 5 + 持久层 3 + channel 单测 7 + ChatFlow 6 + 转换器 4 + 全装配 1 + 架构 6）。坑#21（SSE 总线丢帧双缺陷：空会话订阅/快照缝隙，Flux.create 锁内桥接修正）+ 坑#22（-pl 须 -am）登记《15》v1.4.0。回写：《03》v1.2.0、《04》v1.1.0、《08》v1.2.0、《12》v1.3.0；E2E-M0-4 步骤交付（§7）。真实模型 E2E 待用户回传（E2E-M0-4） |
| 0.9 | 观测出数 | cs-api | Jaeger 可见 chat span；Prometheus 抓到 cs_* 指标 | |
| 0.10 | V-01~V-03 核验回写 | — | 《01》§7 状态更新 + 修订注记 | |

## 5. 决策点

（暂无待拍板项；产生即按「选项表+定案记录」格式追加）

## 6. 验收标准（里程碑 DoD）

1. Widget→SSE 全链通，trace/指标可见；2. ArchUnit/测试门禁 CI 强制；3. V-01~V-03 核验结论回写；4. E2E-M0-1 用户回传销账；5. 复盘提案入 project-optimization（M1批次定案依据）。

## 7. E2E 与热修记录

| 轮次 | 代号 | 结论 | 跟进 |
|---|---|---|---|
| — | E2E-M0-1（交付于M0批5） | 待交付 | — |
| 1 | E2E-M0-4（交付于M0批4，步骤见下） | ⬜ 待用户执行（真实 GLM key） | — |

### E2E-M0-4 最小对话链自测步骤（真实模型，用户侧执行）

前置：本机（或可达的）PostgreSQL 与 Redis；智谱 GLM API Key 一个（OpenAI 兼容端点）。

1. 配置环境（仓库根 `.env` 已有占位，填入后导出）：
   ```bash
   # .env 中：CS_AI_API_KEY=<你的智谱key>  CS_AI_ENABLED=true  CS_TURN_ENGINE=orchestration
   set -a; source .env; set +a
   export CS_DB_URL CS_DB_USER CS_DB_PASSWORD CS_REDIS_HOST CS_REDIS_PORT \
          CS_WEBCHAT_APP_KEY CS_WEBCHAT_APP_SECRET CS_WEBCHAT_TENANT \
          CS_AI_ENABLED CS_AI_API_KEY CS_TURN_ENGINE
   ```
2. 启动（prod 端口 8100 形态 M0批5 落地，本步 dev 8081）：
   ```bash
   mvn -q package -DskipTests && java -jar cs-api/target/cs-api-*.jar
   ```
3. 浏览器打开 `http://localhost:8081/widget/`，发两条消息（如「退款怎么办理」→「多久到账」），预期：
   - 每条消息收到流式 TOKEN 增量后以 DONE 结束（一轮一连接，页面自动重连续接）；
   - 第二条的回答体现上下文（知道在问退款的到账时间）——窗口记忆生效。
4. 数据核验（psql 连 `CS_DB_URL`）：
   ```sql
   SELECT state, model_tier, tokens_in, tokens_out, latency_ms FROM cs_turn ORDER BY created_at DESC LIMIT 2;
   SELECT role, turn_id IS NOT NULL AS bound, left(content, 40) FROM cs_message ORDER BY seq DESC LIMIT 4;
   SELECT seq, event_type FROM cs_session_event ORDER BY seq;
   ```
   预期：两轮 state=COMPLETED、model_tier=T1、tokens_in/out 非空；AI 消息带 turn_id；事件含 ROUTE_DECIDED + MESSAGE_APPENDED×2。
5. 回传：两条回答文本摘要 + 上述三组 SQL 读数（结论落档即可，勿贴 key）。

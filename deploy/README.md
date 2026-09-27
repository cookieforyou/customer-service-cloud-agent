# CSCA 部署与监控接入资产（M0批5 交付，INF-1/E2E-M0-1 消费）

> 纪律提示：改既有 Prometheus 配置前**先备份原 `prometheus.yml`**；CSCA 配置以独立片段追加，
> 便于回滚（INF-1 前置）。镜像全部钉版（坑#06）；分栈 compose 不用 `--remove-orphans`（坑#09）。
> 密钥一律经 `.env`（gitignore 排除）注入，不入仓库。

## 0. PG 前置（首次对 ECS PG 启动，坑#25/#27）

ECS PG（实测 18.0；本地测试镜像 pgvector:pg17，对齐事项见 M0 复盘 O-7）应用账号无 CREATEROLE，
`cs_app` 角色由运维引导；应用迁移（V2）能力自适应跳过。当前形态：本地 IDEA 启动 + 环境变量
直连 ECS PG（本地 E2E 验证期）。

**⚠️ PG 15+ 所有权语义（坑#27，V1 失败根因）**：`DROP SCHEMA public` 后重建，新 schema 归
**执行重建的账号**——管理员重建后应用账号无 CREATE 权限（42501）；且 public 缺失时 Flyway 会把
search_path 原始串 `"$user", public` 误建为字面名 schema。修复 SQL 必须用 `AUTHORIZATION`
把 owner 交给应用账号。

**库重置/修复（管理员执行，务必连接到 `cs_agent` 库；`<app_user>` = CS_DB_USER 的值）**：

```sql
-- 自检：确认当前库与残留 schema
SELECT current_database();
SELECT nspname FROM pg_namespace WHERE nspname NOT LIKE 'pg\_%' ORDER BY 1;

-- 一次性修复：清误建字面名 schema + 重建 public 并交还 owner
DROP SCHEMA IF EXISTS """$user"", public" CASCADE;
DROP SCHEMA IF EXISTS public CASCADE;
CREATE SCHEMA public AUTHORIZATION "<app_user>";

-- 预期结果：自检第二条仅剩 public（owner=应用账号）
```

**角色引导**（管理员执行一次，幂等；先于应用首启为佳）：

```bash
psql "<管理员连接串>/cs_agent" -f deploy/db/bootstrap-roles.sql
```

应用启动即完成迁移（`spring.flyway.schemas: public` 显式钉死目标 schema，缺失时由 Flyway
以应用账号自建、owner 正确——此后不再依赖 search_path 解析）。**运行期 RLS**：应用以表 owner
（非 superuser）连接时受 FORCE RLS 约束，租户 GUC 由应用每事务注入（TenantContext→set_config），
无需人工干预。

**以应用账号直查数据的口径（RLS fail-closed，2026-09-27 E2E 实证）**：应用账号（表 owner、非
superuser）直连查询时无租户 GUC → FORCE RLS 过滤为 0 行且不报错（设计行为）；管理员（superuser）
旁路 RLS 看全量。用应用账号排查须先注入 GUC（租户值 = `CS_WEBCHAT_TENANT`）：

```sql
SELECT set_config('cs.tenant_id', '<CS_WEBCHAT_TENANT 值>', false);   -- 会话级
SELECT * FROM cs_turn ORDER BY created_at DESC LIMIT 3;
```

## 1. 构建（宿主侧，ECS 上执行）

```bash
mvn -DskipTests package
docker build -t csca/cs-api:1.0.0-SNAPSHOT .
```

## 2. 启动 CSCA 栈（cs-api + 自有 OTel Collector）

```bash
cd deploy
cp ../.env .        # 或按 csca-scrape/compose 环境变量清单补齐 .env
docker compose -f docker-compose.csca.yml up -d
docker compose -f docker-compose.csca.yml ps        # 两服务 Up
curl -s http://localhost:8100/actuator/health       # {"status":"UP"...}
```

- dev 宿主直跑（不经容器）时观测走宿主映射：`CS_OTLP_ENDPOINT=http://localhost:4320/v1/traces`。
- Jaeger 目标端点经 `CSCA_JAEGER_OTLP_ENDPOINT` 覆盖（缺省 `http://host.docker.internal:4319`，
  即 kb 栈 Jaeger 的 OTLP HTTP 宿主映射——以 ECS 实际端口为准）。

## 3. 既有 Prometheus 纳管（复用 kb 栈，不新起）

1. 备份既有 `prometheus.yml`；
2. 将 `prometheus/csca-scrape-job.yml` 内容追加到其 `scrape_configs`（或按其 include 组织形态挂独立文件）；
3. 重载：`curl -X POST http://<prometheus-host>:9092/-/reload`（或重启 Prometheus 容器）；
4. 验证：Prometheus UI → Status/Targets 出现 `cs-api` 且 **UP**。

## 4. Grafana 导入

Dashboards → Import → 上传 `grafana/csca-m0-dashboard.json`（数据源选既有 Prometheus）。
预期面板：轮次总量/失败数/整轮 P95/轮次速率/JVM 堆。

## 5. 验证读数（INF-1 回传项）

- Jaeger UI（kb 栈，宿主 16687）→ Service = `cs-api`，发一条消息后可见 `chat.turn` span
  （属性含 cs.tenant_id/cs.session_id/cs.turn_id/langfuse.user.id/langfuse.session.id）；
- Prometheus：`up{job="cs-api"}=1`；发消息后 `cs_turn_total{state="COMPLETED"}` 增长；
- Grafana 盘出数。

## 6. 回滚

- 移除追加的 scrape job 并 reload；
- `docker compose -f docker-compose.csca.yml down`（独立栈，无 kb 栈副作用）。

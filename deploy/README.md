# CSCA 部署与监控接入资产（M0批5 交付，INF-1/E2E-M0-1 消费）

> 纪律提示：改既有 Prometheus 配置前**先备份原 `prometheus.yml`**；CSCA 配置以独立片段追加，
> 便于回滚（INF-1 前置）。镜像全部钉版（坑#06）；分栈 compose 不用 `--remove-orphans`（坑#09）。
> 密钥一律经 `.env`（gitignore 排除）注入，不入仓库。

## 0. PG 前置（首次对 ECS PG 启动，坑#25）

ECS PG（实测 18.0；本地测试镜像 pgvector:pg17，对齐事项见 M0 复盘 O-7）应用账号无 CREATEROLE，
`cs_app` 角色由运维引导；应用迁移（V2）能力自适应跳过。当前形态：本地 IDEA 启动 + 环境变量
直连 ECS PG（本地 E2E 验证期），下列 SQL 经任意 psql 客户端以管理员连接串执行即可。

1. **失败残留清理**（曾以旧 V2 启动过的库；ECS `cs_agent` 当前即此态——V1 已建表、V2 留失败行）。
   全新库无历史数据，推荐直接重置（psql 管理员执行）：
   ```sql
   DROP SCHEMA public CASCADE; CREATE SCHEMA public;
   ```
   （保留数据时改为：`DELETE FROM flyway_schema_history WHERE version = '2';`）
2. **角色引导**（管理员执行一次，幂等；先于应用首启为佳）：
   ```bash
   psql "<管理员连接串>" -f deploy/db/bootstrap-roles.sql
   ```
3. 应用启动即完成 V1-V3 迁移。**运行期 RLS**：应用以表 owner（非 superuser）连接时受
   FORCE RLS 约束，租户 GUC 由应用每事务注入（TenantContext→set_config），无需人工干预。

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

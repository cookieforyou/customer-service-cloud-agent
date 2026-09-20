# CSCA 部署与监控接入资产（M0批5 交付，INF-1/E2E-M0-1 消费）

> 纪律提示：改既有 Prometheus 配置前**先备份原 `prometheus.yml`**；CSCA 配置以独立片段追加，
> 便于回滚（INF-1 前置）。镜像全部钉版（坑#06）；分栈 compose 不用 `--remove-orphans`（坑#09）。
> 密钥一律经 `.env`（gitignore 排除）注入，不入仓库。

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

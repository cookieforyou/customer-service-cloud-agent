# 08 · 渠道接入层

> 版本 v1.0.0 ｜ 2026-09-20 ｜ 初版。依赖《01》D-07/D-12/D-13，《03》幂等。

## 1. ChannelAdapter SPI

```java
public interface ChannelAdapter {
  String channel();                                   // webchat | openapi | (预留 im_dingtalk/wechat_work/email)
  InboundResult onInbound(RawChannelMessage raw);     // 验签→幂等→归一化→投递会话域
  void onOutbound(OutboundEnvelope env);              // 帧编码→渠道投递（webchat=本层直出 SSE）
}
```

- 统一入站信封 `InboundMessage{tenantId, channel, channelMsgId, visitorId?, userId?, text, attachments?, occurredAt}`；统一出站信封 `OutboundEnvelope{sessionId, turnId, frames[]}`。
- 渠道差异（签名校验、消息格式、重试语义）全部封装在适配器内；会话域与编排引擎对渠道无感知。

## 2. 初始渠道

| 渠道 | 形态 | 鉴权 | 说明 |
|---|---|---|---|
| `webchat` | REST + SSE | 访客 JWT（匿名签发，可绑定业务用户）；站点级 appKey+sign（防冒用） | 面向网页嵌入 Widget；支持断线重连 |
| `openapi` | REST（同步 JSON）/ 回调 | OAuth2 client_credentials（Casdoor 服务身份）或 API Key | 面向企业内部系统以 API 嵌入客服能力；返回完整应答（非流式）+ turnId |

预留（M3+，按需）：钉钉 Stream 机器人、企业微信客服、邮件（邮件渠道需线程级去重与垃圾/自动邮件过滤，参考行业实践：单封多问拆解、独立 agent 处理）。

## 3. 入站处理管线（webchat 为例）

```
验签(appKey+timestamp+sign, ±5min) → 访客JWT校验 → 幂等((tenant,channel,msgId) Redis SETNX 30s + DB 唯一键兜底)
  → 限流(per-visitor RPM 20 / per-tenant RPM+TPM) → 归一化(附件→引用) → 会话域投递
  → 立即回 ACK 帧（含 turnId），应答走 SSE 异步
```

## 4. SSE 帧协议 v1（本平台权威帧族）

`GET /api/v1/chat/sessions/{sessionId}/stream`（`text/event-stream`）；id = 帧序号（补发游标）。

| 帧 | payload | 说明 |
|---|---|---|
| `ACK` | `{turnId, messageId}` | 消息受理 |
| `TOKEN` | `{delta}` | 增量文本 |
| `MESSAGE` | `{role, text}` | 整帧消息（卡片/兜底话术/坐席消息复用） |
| `TOOL` | `{toolName, phase: STARTED\|RESULT\|APPROVAL_REQUIRED, approvalId?, summary}` | 工具过程透出（L2 审批入口） |
| `TRACE` | `{sources[], refs[], latencyMs}` | 检索证据（对齐知识服务 TRACE 语义） |
| `ESCALATION` | `{state, queuePosition?, etaSeconds?}` | 转人工状态（入队/接通） |
| `CSAT` | `{questionId}` | 结束满意度邀评（策略触发） |
| `DONE` | `{turnId, messageId, cacheHit, modelTier}` | 轮次完成 |
| `ERROR` | `{code, message, retriable}` | `code` 用平台错误码 |
| 注释帧 `:ping` | — | 15s 心跳 |

openapi 渠道不使用帧协议，返回 `{answer, turnId, refs[], toolCalls[], resolution?}` 的同步 JSON。

## 5. 断线重连与补发

- 客户端带 `Last-Event-ID` 重连；服务端从 ring buffer（`cs:sse:buffer:{sessionTurnId}`，容量 256 帧，TTL 5min，见《03》§6）补发其后帧；越界（buffer 已淘汰）则回放当前 turn 的 `MESSAGE` 全帧 + 提示。
- 转人工段：WebSocket（坐席台）/ webchat 用户侧仍走 SSE（坐席消息经 `MESSAGE` 帧下发），单向流对用户端足够。

## 6. 限流与背压（LLM10 缓解）

| 维度 | 初始值 | 超限行为 |
|---|---|---|
| per-visitor | 20 RPM / 200 RPD | `RATE_LIMITED` + 温和话术；连续超限 5 次→封禁 10min |
| per-tenant | RPM/TPM 配额（`cs_quota_usage` 计量） | 超限→新会话拒绝+告警；进行中会话降级到 T0 |
| 全局 | in-flight LLM 并发上限（信号量） | 排队（虚拟线程等待）>5s→提示繁忙 |

令牌桶实现 Redisson（Redis），滑窗计数 PG 小时聚合（账单/分析用）。

## 7. 多租户与安全要点（详见《11》）

- `tenantId` 来自 JWT/签名校验后的服务端映射，**客户端传入一律忽略**。
- 渠道 webhook 类适配器（预留 IM）须验签 + 时间窗防重放；消息附件先落对象存储引用再入会话（不内联 base64）。
- CORS 白名单按租户站点配置；SSE 响应禁用代理缓冲（`X-Accel-Buffering: no`）。

## 8. 修订注记

- v1.0.0（2026-09-20）：初版。

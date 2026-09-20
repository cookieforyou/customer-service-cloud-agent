package com.enterprise.cs.conversation.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话上下文对外端口（《02》§5.1：跨模块同步只走 api 接口；channel 经此持久化会话与入站消息）。
 * 命令与结果即跨模块契约（record 嵌套于端口，契约与能力同处）。阶段：M0批3 起供 channel 消费；
 * M0批4 增轮次生命周期与窗口读拼（orchestration 消费，《03》§3/《04》§2）。
 */
public interface ConversationPort {

    SessionOpened openSession(OpenSessionCmd cmd);

    AppendedMessage appendInbound(AppendInboundCmd cmd);

    Optional<SessionInfo> sessionInfo(UUID sessionId);

    /** 幂等兜底补偿读（独立新事务）：按 (tenant, channel, channelMsgId) 查既有消息 id。 */
    Optional<UUID> inboundMessageId(String tenantId, String channel, String channelMsgId);

    /** 开轮（《04》§2）：cs_turn(RUNNING) + 入站消息回填 turn_id + ROUTE_DECIDED 事件。 */
    void startTurn(StartTurnCmd cmd);

    /** 收轮：cs_turn 终态与计量回填 + AI 消息落库 + MESSAGE_APPENDED 事件；返回 AI 消息 id。 */
    UUID finishTurn(FinishTurnCmd cmd);

    /** 窗口读拼（SessionMemoryAdvisor，《03》§3.2 窗口步）：旧→新，排除本轮入站消息。 */
    List<WindowMessage> recentWindow(UUID sessionId, int limit, UUID excludeMessageId);

    /** 会话只读信息（渠道归属校验用，M0批3 起提供）。 */
    record SessionInfo(UUID id, String tenantId, String channel, String visitorId, String state) {
    }

    /** 开会话命令：tenantId 取自身份 claims（服务端注入，禁客户端传入，《11》§6）。 */
    record OpenSessionCmd(String tenantId, String channel, String visitorId) {
    }

    record SessionOpened(UUID sessionId) {
    }

    record AppendInboundCmd(UUID sessionId, String tenantId, String channel,
                            String channelMsgId, String content) {
    }

    /** duplicate=true 表示 (tenant,channel,channelMsgId) 幂等键已存在（《03》§5）。 */
    record AppendedMessage(UUID messageId, boolean duplicate) {
    }

    record StartTurnCmd(UUID sessionId, UUID turnId, UUID inboundMessageId,
                        String modelTier, String routeDecisionJson) {
    }

    record FinishTurnCmd(UUID turnId, String state, Integer tokensIn, Integer tokensOut,
                         Integer latencyMs, String assistantContent) {
    }

    /** 窗口消息（role ∈ USER/AI），《03》§1 消息角色的窗口子集。 */
    record WindowMessage(String role, String content) {
    }
}

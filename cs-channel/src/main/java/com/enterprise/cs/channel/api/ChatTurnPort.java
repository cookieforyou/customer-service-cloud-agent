package com.enterprise.cs.channel.api;

import java.util.UUID;

/**
 * 轮次引擎端口：消息受理后的一轮应答生成（《04》M0批4 由 orchestration 实现取代 echo 桩）。
 * 实现方经 FrameSink 按任意顺序/节奏发帧；DONE/ERROR 帧后流即完成。
 */
public interface ChatTurnPort {

    void runTurn(TurnCommand command, FrameSink sink) throws Exception;

    /** turnId/messageId 由受理侧生成并传递（DONE 帧与幂等回执引用）；visitorId 供观测根 span 归组（《10》§2）。 */
    record TurnCommand(UUID sessionId, UUID turnId, UUID messageId, String tenantId, String visitorId, String userText) {
    }
}

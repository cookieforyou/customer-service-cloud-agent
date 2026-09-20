package com.enterprise.cs.channel.infra;

import com.enterprise.cs.commons.constant.CsConstants;
import com.enterprise.cs.conversation.api.ConversationPort;
import com.enterprise.cs.channel.api.ChatTurnPort;
import com.enterprise.cs.orchestration.api.SupervisorTurnPort;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 轮次引擎切换装配（《08》§3）：cs.channel.turn-engine=orchestration 时以 supervisor 引擎
 * 取代 echo 桩（M0批4 起）。本适配器承担轮次生命周期落库（开轮/收轮，经 conversation——
 * 模块白名单下 orchestration 不依赖 conversation，保持纯运行时）；assistant 全文经 token 增量累积。
 * M0批5：chat.turn 根 Observation（《10》§2 属性 cs.tenant/session/turn + langfuse.user/session，
 * ObservationRootAdvisor 链化与 intent/agent_version 属性随 M1 Advisor 链）+ cs_turn_* 指标 +
 * 事件表 trace_id 回填（同线程 Span 上下文直达 conversation appendEvent）。
 */
@Configuration
@ConditionalOnProperty(name = "cs.channel.turn-engine", havingValue = "orchestration")
public class OrchestrationTurnEngineConfig {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationTurnEngineConfig.class);

    @Bean
    public ChatTurnPort chatTurnPort(SupervisorTurnPort supervisor, ConversationPort conversation,
                                     ChatTurnMetrics metrics,
                                     ObjectProvider<ObservationRegistry> observationRegistry) {
        return (command, sink) -> Observation
                .createNotStarted("chat.turn", observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
                .lowCardinalityKeyValue("cs.tenant_id", command.tenantId())
                .lowCardinalityKeyValue("cs.channel", "webchat")
                .highCardinalityKeyValue("cs.session_id", command.sessionId().toString())
                .highCardinalityKeyValue("cs.turn_id", command.turnId().toString())
                .highCardinalityKeyValue("langfuse.user.id", command.visitorId())
                .highCardinalityKeyValue("langfuse.session.id", command.sessionId().toString())
                .observe(() -> {
                    try {
                        runTurn(supervisor, conversation, metrics, command, sink);
                    } catch (Exception e) {
                        // Observation 记 error 后以运行时异常滚出——由 ChatService 兜底 catch 发 ERROR 帧
                        throw new IllegalStateException("chat.turn 执行失败: " + command.turnId(), e);
                    }
                });
    }

    private void runTurn(SupervisorTurnPort supervisor, ConversationPort conversation,
                         ChatTurnMetrics metrics, ChatTurnPort.TurnCommand command,
                         com.enterprise.cs.channel.api.FrameSink sink) throws Exception {
        long startedAt = System.nanoTime();
        conversation.startTurn(new ConversationPort.StartTurnCmd(
                command.sessionId(), command.turnId(), command.messageId(),
                CsConstants.MODEL_TIER_T1,
                "{\"decision\":\"direct\",\"tier\":\"" + CsConstants.MODEL_TIER_T1 + "\"}"));
        StringBuilder answer = new StringBuilder();
        supervisor.runTurn(
                new SupervisorTurnPort.TurnRequest(command.sessionId(), command.turnId(),
                        command.messageId(), command.tenantId(), command.userText()),
                new SupervisorTurnPort.TurnFrames() {
                    @Override
                    public void token(String delta) {
                        answer.append(delta);
                        sink.token(delta);
                    }

                    @Override
                    public void completed(Integer tokensIn, Integer tokensOut, Integer latencyMs) {
                        conversation.finishTurn(new ConversationPort.FinishTurnCmd(
                                command.turnId(), CsConstants.TURN_STATE_COMPLETED,
                                tokensIn, tokensOut, latencyMs, answer.toString()));
                        metrics.turnFinished(CsConstants.TURN_STATE_COMPLETED, latencyMs != null ? latencyMs
                                : java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
                        sink.done();
                    }

                    @Override
                    public void failed(String code, String message) {
                        try {
                            conversation.finishTurn(new ConversationPort.FinishTurnCmd(
                                    command.turnId(), CsConstants.TURN_STATE_FAILED,
                                    null, null, null, answer.toString()));
                        } catch (Exception e) {
                            log.warn("finishTurn(FAILED) 补偿失败: turnId={}", command.turnId(), e);
                        }
                        metrics.turnFinished(CsConstants.TURN_STATE_FAILED,
                                java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
                        sink.error(code, message);
                    }
                });
    }
}

package com.enterprise.cs.channel.infra;

import com.enterprise.cs.commons.constant.CsConstants;
import com.enterprise.cs.conversation.api.ConversationPort;
import com.enterprise.cs.channel.api.ChatTurnPort;
import com.enterprise.cs.orchestration.api.SupervisorTurnPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 轮次引擎切换装配（《08》§3）：cs.channel.turn-engine=orchestration 时以 supervisor 引擎
 * 取代 echo 桩（M0批4 起）。本适配器承担轮次生命周期落库（开轮/收轮，经 conversation——
 * 模块白名单下 orchestration 不依赖 conversation，保持纯运行时）；assistant 全文经 token 增量累积。
 */
@Configuration
@ConditionalOnProperty(name = "cs.channel.turn-engine", havingValue = "orchestration")
public class OrchestrationTurnEngineConfig {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationTurnEngineConfig.class);

    @Bean
    public ChatTurnPort chatTurnPort(SupervisorTurnPort supervisor, ConversationPort conversation) {
        return (command, sink) -> {
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
                            sink.error(code, message);
                        }
                    });
        };
    }
}

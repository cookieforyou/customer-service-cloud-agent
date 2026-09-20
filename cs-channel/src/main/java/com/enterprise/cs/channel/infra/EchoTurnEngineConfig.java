package com.enterprise.cs.channel.infra;

import com.enterprise.cs.channel.api.ChatTurnPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * echo 轮次引擎桩（M0批3）：分片回显用户消息，验证帧协议/补发/幂等全链。
 * 属性开关 cs.channel.turn-engine=echo（缺省）；M0批4 起由 orchestration 实现接管（=orchestration）。
 */
@Configuration
public class EchoTurnEngineConfig {

    @Bean
    @ConditionalOnProperty(name = "cs.channel.turn-engine", havingValue = "echo", matchIfMissing = true)
    public ChatTurnPort echoTurnEngine() {
        return (command, sink) -> {
            String text = (command.userText() == null || command.userText().isBlank())
                    ? "（空消息）" : command.userText();
            sink.token("已收到您的消息：");
            for (int i = 0; i < text.length(); i += 6) {
                Thread.sleep(60);
                sink.token(text.substring(i, Math.min(i + 6, text.length())));
            }
            sink.done();
        };
    }
}

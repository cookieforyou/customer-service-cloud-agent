package com.enterprise.cs.channel.infra;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * 轮次业务指标（《10》§5 M0 子集）：cs_turn_total{state} 计数 + cs_chat_total_seconds 整轮时延直方图。
 * 首响指标（cs_chat_first_token_seconds）需引擎侧首 token 时点回传，随 M1 TurnFrames 契约扩展落地。
 */
@Component
public class ChatTurnMetrics {

    private final MeterRegistry registry;

    public ChatTurnMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void turnFinished(String state, long latencyMs) {
        registry.counter("cs_turn_total", "state", state).increment();
        Timer.builder("cs_chat_total_seconds")
                .description("整轮端到端时延（受理→DONE/ERROR）")
                .publishPercentileHistogram()
                .register(registry)
                .record(java.time.Duration.ofMillis(latencyMs));
    }
}

package com.enterprise.cs.orchestration.app;

import com.enterprise.cs.ai.api.ModelTier;
import com.enterprise.cs.ai.api.RoutingChatModel;
import com.enterprise.cs.commons.constant.ErrorCodes;
import com.enterprise.cs.orchestration.api.SupervisorTurnPort;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * supervisor 引擎最小链单测（《04》M0批4）：假 RoutingChatModel（真对象+Fake 载体）+ 透传
 * Advisor——验证 token 增量顺序、completed 计量（usage 末块 + 时延）、失败路径 failed 帧。
 * 落库不在本模块（channel 适配器职责），故无 ConversationPort 依赖。纯单测不起容器。
 */
class SupervisorTurnEngineTest {

    static final class FakeT1 implements ChatModel {

        final Flux<ChatResponse> stream;

        FakeT1(Flux<ChatResponse> stream) {
            this.stream = stream;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("最小链仅流式");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return stream;
        }
    }

    static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    static ChatResponse usageOnly() {
        return ChatResponse.builder()
                .generations(List.of())
                .metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(11, 7)).build())
                .build();
    }

    /** 透传 Advisor（不起记忆读拼——该职责在 conversation 模块单测覆盖）。 */
    static StreamAdvisor passthrough() {
        return new StreamAdvisor() {
            @Override
            public String getName() {
                return "passthrough";
            }

            @Override
            public int getOrder() {
                return 0;
            }

            @Override
            public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
                return chain.nextStream(request);
            }
        };
    }

    private static RoutingChatModel routing(Flux<ChatResponse> stream) {
        return new RoutingChatModel(java.util.Map.of(ModelTier.T1_PRIMARY, new FakeT1(stream)),
                ModelTier.T1_PRIMARY);
    }

    static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public T getObject() {
                throw new IllegalStateException("单测 provider 仅支持 getIfAvailable");
            }

            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }

    private static SupervisorTurnPort.TurnRequest request() {
        return new SupervisorTurnPort.TurnRequest(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "t-a", "退款怎么办理");
    }

    @Test
    void streamsTokensInOrderThenCompletedWithUsage() throws Exception {
        List<String> tokens = new ArrayList<>();
        AtomicInteger tokensIn = new AtomicInteger(-1);
        AtomicInteger tokensOut = new AtomicInteger(-1);
        AtomicInteger latencyMs = new AtomicInteger(-1);
        SupervisorTurnPort engine = new SupervisorTurnEngine(
                provider(routing(Flux.just(chunk("你"), chunk("好"), usageOnly()))), passthrough());

        engine.runTurn(request(), new SupervisorTurnPort.TurnFrames() {
            @Override
            public void token(String delta) {
                tokens.add(delta);
            }

            @Override
            public void completed(Integer in, Integer out, Integer latency) {
                tokensIn.set(in);
                tokensOut.set(out);
                latencyMs.set(latency);
            }

            @Override
            public void failed(String code, String message) {
                throw new AssertionError("不应失败: " + message);
            }
        });

        assertThat(tokens).containsExactly("你", "好");
        assertThat(tokensIn.get()).isEqualTo(11);
        assertThat(tokensOut.get()).isEqualTo(7);
        assertThat(latencyMs.get()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void modelFailureSurfacesAsFailedSignal() throws Exception {
        List<String> codes = new ArrayList<>();
        SupervisorTurnPort engine = new SupervisorTurnEngine(
                provider(routing(Flux.error(new RuntimeException("boom")))), passthrough());

        engine.runTurn(request(), new SupervisorTurnPort.TurnFrames() {
            @Override
            public void token(String delta) {
            }

            @Override
            public void completed(Integer tokensIn, Integer tokensOut, Integer latencyMs) {
                throw new AssertionError("不应成轮");
            }

            @Override
            public void failed(String code, String message) {
                codes.add(code);
            }
        });

        assertThat(codes).containsExactly(ErrorCodes.INTERNAL_ERROR);
    }
}

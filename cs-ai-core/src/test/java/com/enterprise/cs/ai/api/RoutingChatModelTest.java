package com.enterprise.cs.ai.api;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RoutingChatModel 骨架单测（《04》§4）：缺省档直连、TierRoutingOptions 显式解析、
 * 未配置 tier 快失败、getOptions 透传。纯单测，FakeChatModel 不起容器。
 */
class RoutingChatModelTest {

    static final class FakeChatModel implements ChatModel {

        final String marker;
        final ChatOptions options;

        FakeChatModel(String marker, ChatOptions options) {
            this.marker = marker;
            this.options = options;
        }

        static ChatResponse resp(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return resp(marker);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(resp(marker));
        }

        @Override
        public ChatOptions getOptions() {
            return options;
        }
    }

    private static String text(ChatResponse response) {
        return response.getResult().getOutput().getText();
    }

    @Test
    void defaultRoutesToConfiguredPrimaryTier() {
        RoutingChatModel routing = new RoutingChatModel(
                Map.of(ModelTier.T1_PRIMARY, new FakeChatModel("T1", null)), ModelTier.T1_PRIMARY);
        assertThat(text(routing.call(new Prompt("hi")))).isEqualTo("T1");
        assertThat(routing.stream(new Prompt("hi")).blockLast()).isNotNull();
    }

    @Test
    void optionsTierOverridesDefault() {
        RoutingChatModel routing = new RoutingChatModel(Map.of(
                ModelTier.T1_PRIMARY, new FakeChatModel("T1", null),
                ModelTier.T0_AUX, new FakeChatModel("T0", null)), ModelTier.T1_PRIMARY);
        Prompt prompt = new Prompt("hi", new TierRoutingOptions(ModelTier.T0_AUX));
        assertThat(text(routing.call(prompt))).isEqualTo("T0");
    }

    @Test
    void unconfiguredTierFailsFast() {
        RoutingChatModel routing = new RoutingChatModel(
                Map.of(ModelTier.T1_PRIMARY, new FakeChatModel("T1", null)), ModelTier.T1_PRIMARY);
        Prompt prompt = new Prompt("hi", new TierRoutingOptions(ModelTier.T2_FALLBACK));
        assertThatThrownBy(() -> routing.call(prompt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("T2_FALLBACK");
    }

    @Test
    void defaultTierMustBeConfigured() {
        assertThatThrownBy(() -> new RoutingChatModel(Map.of(), ModelTier.T1_PRIMARY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getOptionsDelegatesToDefaultTierModel() {
        ChatOptions options = ChatOptions.builder().build();
        RoutingChatModel routing = new RoutingChatModel(
                Map.of(ModelTier.T1_PRIMARY, new FakeChatModel("T1", options)), ModelTier.T1_PRIMARY);
        assertThat(routing.getOptions()).isSameAs(options);
    }
}

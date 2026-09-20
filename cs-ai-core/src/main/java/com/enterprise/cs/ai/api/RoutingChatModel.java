package com.enterprise.cs.ai.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 分级路由模型（《04》§4 D-03，Spring AI 2.0：ChatModel 已含 StreamingChatModel，call/stream 双实现）。
 * M0批4 骨架 = T1 直连 + tier 解析缝（{@link TierRoutingOptions} 显式指定或缺省档）；
 * 熔断/T2 回退/成本路由与 cs_model_route_total 计量挂 M1（当前 reason 恒 DEFAULT/OPTIONS）。
 * 多 ChatModel Bean 场景由装配侧标 @Primary 消歧（坑#04 姊妹实证）。
 */
public class RoutingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(RoutingChatModel.class);

    private final Map<ModelTier, ChatModel> tiers;
    private final ModelTier defaultTier;

    public RoutingChatModel(Map<ModelTier, ChatModel> tiers, ModelTier defaultTier) {
        if (!tiers.containsKey(defaultTier)) {
            throw new IllegalArgumentException("defaultTier 未配置载体: " + defaultTier);
        }
        this.tiers = Map.copyOf(tiers);
        this.defaultTier = defaultTier;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return resolve(prompt).call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return resolve(prompt).stream(prompt);
    }

    @Override
    public ChatOptions getOptions() {
        return tiers.get(defaultTier).getOptions();
    }

    private ChatModel resolve(Prompt prompt) {
        ModelTier tier = prompt.getOptions() instanceof TierRoutingOptions options ? options.tier() : defaultTier;
        ChatModel target = tiers.get(tier);
        if (target == null) {
            throw new IllegalStateException("ModelTier " + tier + " 未配置载体（M0批4 仅 T1 直连，T0/T2 于 M1 接入）");
        }
        if (tier != defaultTier) {
            log.debug("RoutingChatModel 解析 tier={}（reason=OPTIONS）", tier);
        }
        return target;
    }
}

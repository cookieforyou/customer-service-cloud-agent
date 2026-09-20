package com.enterprise.cs.ai.api;

import org.springframework.ai.chat.prompt.DefaultChatOptions;

/**
 * 路由提示 options（《04》§4）：携带 {@link ModelTier} 供 {@link RoutingChatModel} 解析目标载体。
 * 不携带时走路由模型缺省档（M0批4 = T1_PRIMARY）。除 tier 外不承载采样参数——
 * 请求级参数定制 M1 随路由矩阵一并定案。
 */
public final class TierRoutingOptions extends DefaultChatOptions {

    private final ModelTier tier;

    public TierRoutingOptions(ModelTier tier) {
        super(null, null, null, null, null, null, null, null);
        this.tier = tier;
    }

    public ModelTier tier() {
        return tier;
    }
}

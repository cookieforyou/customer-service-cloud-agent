package com.enterprise.cs.ai.api;

import com.enterprise.cs.commons.constant.CsConstants;

/**
 * 模型分级（《04》§4 D-03）：T0 辅助族（意图/摘要/分类）/ T1 主对话 / T2 熔断回退 / T3 跨家族质检 judge。
 * code 为 cs_turn.model_tier 列值域（协议字面量收敛于 CsConstants，纪律 10）。
 */
public enum ModelTier {

    T0_AUX(CsConstants.MODEL_TIER_T0),
    T1_PRIMARY(CsConstants.MODEL_TIER_T1),
    T2_FALLBACK(CsConstants.MODEL_TIER_T2),
    T3_JUDGE(CsConstants.MODEL_TIER_T3);

    private final String code;

    ModelTier(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}

package com.enterprise.cs.commons.constant;

/**
 * CSCA 平台级常量收敛（AGENTS.md 纪律 10：跨类/跨模块共享的协议性字面量收口于此）。
 * 建立于 M0批1；后续分域常量按《02》§7 命名约定在commons内分包生长。
 */
public final class CsConstants {

    /** 平台代号。 */
    public static final String PLATFORM_CODE = "CSCA";

    /** REST 路径版本前缀（《02》§7）。 */
    public static final String API_V1 = "/api/v1";

    /** Advisor 上下文键：会话 ID（channel→orchestration 写入，SessionMemoryAdvisor 读取，《03》§3）。 */
    public static final String CTX_SESSION_ID = "cs.sessionId";

    /** Advisor 上下文键：本轮入站消息 ID（窗口读拼时排除自身）。 */
    public static final String CTX_EXCLUDE_MESSAGE_ID = "cs.excludeMessageId";

    /** 模型分级代码（《04》§4，cs_turn.model_tier 列值域）：T0 辅助 / T1 主 / T2 回退 / T3 质检。 */
    public static final String MODEL_TIER_T0 = "T0";
    public static final String MODEL_TIER_T1 = "T1";
    public static final String MODEL_TIER_T2 = "T2";
    public static final String MODEL_TIER_T3 = "T3";

    /** 轮次状态（《04》§2，cs_turn.state 列值域）：channel 适配器与 conversation 落库共享。 */
    public static final String TURN_STATE_RUNNING = "RUNNING";
    public static final String TURN_STATE_COMPLETED = "COMPLETED";
    public static final String TURN_STATE_FAILED = "FAILED";

    private CsConstants() {
    }
}

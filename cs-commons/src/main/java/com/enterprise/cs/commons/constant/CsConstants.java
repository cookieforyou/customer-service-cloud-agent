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

    private CsConstants() {
    }
}

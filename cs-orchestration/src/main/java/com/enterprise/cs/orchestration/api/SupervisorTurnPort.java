package com.enterprise.cs.orchestration.api;

import java.util.UUID;

/**
 * 主编排轮次端口（main_supervisor，《04》§1/§2）：channel 侧适配器（ChatTurnPort 实现）驱动，
 * 本模块保持纯 Agent 运行时——轮次生命周期落库由驱动侧经 conversation 完成（模块白名单，
 * orchestration 不依赖 conversation；记忆注入经 SessionMemoryAdvisor 以 Spring AI 外部类型装配）。
 * 阶段：M0批4 最小链（T1 直连生成 + 流式帧）；路由/派生/工具随 M1-M2 生长。
 */
public interface SupervisorTurnPort {

    /** 阻塞执行直至成轮或失败（驱动侧以虚拟线程调用）。 */
    void runTurn(TurnRequest request, TurnFrames frames) throws Exception;

    record TurnRequest(UUID sessionId, UUID turnId, UUID messageId, String tenantId, String userText) {
    }

    /** 轮次出帧契约：token 增量 + 成轮计量信号 + 失败信号（驱动侧据此落库并发 DONE/ERROR 帧）。 */
    interface TurnFrames {

        void token(String delta);

        /** 成轮：计量由运行时统计（usage 末块 + 端到端时延），assistant 全文由驱动侧累积。 */
        void completed(Integer tokensIn, Integer tokensOut, Integer latencyMs);

        void failed(String code, String message);
    }
}

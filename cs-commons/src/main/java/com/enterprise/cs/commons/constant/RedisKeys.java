package com.enterprise.cs.commons.constant;

/**
 * Redis 键规范（《12》§3 唯一权威键形的常量收敛，AGENTS.md 纪律 10）。
 * 键模板与《12》§3 表一一对应；Redisson 装配与业务使用自 M0批3（幂等）起接入。阶段：M0批2 数据基线。
 */
public final class RedisKeys {

    /** 键空间总前缀。 */
    public static final String PREFIX = "cs";

    private RedisKeys() {
    }

    /** 会话热态缓存：cs:session:state:{sessionId}（Hash，TTL 25h 滑动）。 */
    public static String sessionState(String sessionId) {
        return "cs:session:state:" + sessionId;
    }

    /** 入站幂等前置判重：cs:idem:{tenantId}:{channel}:{msgId}（SETNX，TTL 30s）。 */
    public static String idem(String tenantId, String channel, String channelMsgId) {
        return "cs:idem:" + tenantId + ":" + channel + ":" + channelMsgId;
    }

    /** 单会话单进行轮锁：cs:lock:turn:{sessionId}（Redisson 锁，TTL 5s）。 */
    public static String turnLock(String sessionId) {
        return "cs:lock:turn:" + sessionId;
    }

    /** 分配调度互斥锁：cs:lock:scheduler（Redisson，M2批1 启用）。 */
    public static final String SCHEDULER_LOCK = "cs:lock:scheduler";

    /** SSE 帧补发缓冲：cs:sse:buffer:{sessionTurnId}（List ≤256，TTL 5min）。 */
    public static String sseBuffer(String sessionTurnId) {
        return "cs:sse:buffer:" + sessionTurnId;
    }

    /** 访客分钟级限流计数：cs:ratelimit:visitor:{visitorId}:m:{yyyyMMddHHmm}（《08》§6）。 */
    public static String rateVisitorMinute(String visitorId, String minute) {
        return "cs:ratelimit:visitor:" + visitorId + ":m:" + minute;
    }

    /** 访客日级限流计数：cs:ratelimit:visitor:{visitorId}:d:{yyyyMMdd}（《08》§6）。 */
    public static String rateVisitorDay(String visitorId, String day) {
        return "cs:ratelimit:visitor:" + visitorId + ":d:" + day;
    }
}

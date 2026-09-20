package com.enterprise.cs.channel.infra;

import tools.jackson.databind.json.JsonMapper;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 入站幂等（《03》§5）：Redis SETNX 前置守卫（30s）+ 回执缓存（5min）；
 * DB 唯一键兜底路径见 ConversationService.appendInbound。
 */
@Component
public class IdempotencyService {

    private static final Duration GUARD_TTL = Duration.ofSeconds(30);
    private static final Duration ACK_TTL = Duration.ofMinutes(5);
    private static final String ACK_SUFFIX = ":ack";

    private final RedissonClient redisson;
    private final JsonMapper json;

    public IdempotencyService(RedissonClient redisson, JsonMapper json) {
        this.redisson = redisson;
        this.json = json;
    }

    public record Ack(UUID turnId, UUID messageId) {
    }

    /** true=守卫已占（重复请求），返回缓存回执（可能因过期为空）。 */
    public Optional<Ack> acquire(String idemKey) {
        RBucket<String> guard = redisson.getBucket(idemKey);
        if (!guard.setIfAbsent("1", GUARD_TTL)) {
            return Optional.ofNullable(readAck(idemKey));
        }
        return Optional.empty();
    }

    public void store(String idemKey, UUID turnId, UUID messageId) {
        try {
            redisson.getBucket(idemKey + ACK_SUFFIX)
                    .set(json.writeValueAsString(new Ack(turnId, messageId)), ACK_TTL);
        } catch (Exception ignored) {
            // 回执缓存写失败不阻断主链路（DB 唯一键兜底仍生效）
        }
    }

    public void release(String idemKey) {
        redisson.getBucket(idemKey).delete();
    }

    private Ack readAck(String idemKey) {
        String raw = redisson.<String>getBucket(idemKey + ACK_SUFFIX).get();
        if (raw == null) {
            return null;
        }
        try {
            return json.readValue(raw, Ack.class);
        } catch (Exception e) {
            return null;
        }
    }
}

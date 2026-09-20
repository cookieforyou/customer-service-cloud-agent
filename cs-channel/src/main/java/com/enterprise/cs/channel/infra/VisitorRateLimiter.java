package com.enterprise.cs.channel.infra;

import com.enterprise.cs.commons.constant.ErrorCodes;
import com.enterprise.cs.commons.constant.RedisKeys;
import com.enterprise.cs.commons.exception.BusinessException;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 访客限流（《08》§6 固定窗口）：分钟 RPM + 日 RPD 双计数（Redis）。
 * 拒绝也计数（固定窗口语义，防刷取向）；超限抛 RATE_LIMITED（HTTP 429）。
 */
@Component
public class VisitorRateLimiter {

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RedissonClient redisson;
    private final ChannelLimitsProperties limits;

    public VisitorRateLimiter(RedissonClient redisson, ChannelLimitsProperties limits) {
        this.redisson = redisson;
        this.limits = limits;
    }

    public void check(String visitorId) {
        LocalDateTime now = LocalDateTime.now();

        RAtomicLong minute = redisson.getAtomicLong(RedisKeys.rateVisitorMinute(visitorId, now.format(MINUTE)));
        long minuteCount = minute.incrementAndGet();
        if (minuteCount == 1) {
            minute.expire(Duration.ofSeconds(61));
        }
        if (minuteCount > limits.visitorRpm()) {
            throw BusinessException.of(ErrorCodes.RATE_LIMITED, "请求过于频繁，请稍后再试");
        }

        RAtomicLong day = redisson.getAtomicLong(RedisKeys.rateVisitorDay(visitorId, now.format(DAY)));
        long dayCount = day.incrementAndGet();
        if (dayCount == 1) {
            day.expire(Duration.ofHours(25));
        }
        if (dayCount > limits.visitorRpd()) {
            throw BusinessException.of(ErrorCodes.RATE_LIMITED, "今日请求已达上限");
        }
    }
}

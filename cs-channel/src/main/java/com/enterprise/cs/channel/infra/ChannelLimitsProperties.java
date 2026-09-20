package com.enterprise.cs.channel.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 渠道限流配置（《08》§6）。 */
@ConfigurationProperties("cs.channel.limits")
public record ChannelLimitsProperties(int visitorRpm, int visitorRpd) {

    public ChannelLimitsProperties {
        if (visitorRpm <= 0) {
            visitorRpm = 20;
        }
        if (visitorRpd <= 0) {
            visitorRpd = 200;
        }
    }
}

package com.enterprise.cs.infra.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台自签访客 JWT 配置（《11》§7 v1.2.0：访客令牌由平台签发，与 Casdoor 双 issuer 并存）。
 * 密钥经环境注入（PEM）；未配置时启动生成临时密钥对（仅 dev/sit 可接受，重启失效并告警）。
 */
@ConfigurationProperties("cs.auth.visitor")
public record VisitorAuthProperties(
        String issuer,
        String publicKeyPem,
        String privateKeyPem,
        java.time.Duration ttl) {

    public VisitorAuthProperties {
        if (issuer == null || issuer.isBlank()) {
            issuer = "urn:csca:visitor";
        }
        if (ttl == null) {
            ttl = java.time.Duration.ofHours(24);
        }
    }
}

package com.enterprise.cs.infra.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Casdoor 资源服务器配置（《11》§7 D-12）：坐席/服务/管理员身份走 Casdoor JWT。
 * jwk-set-uri 与 public-key-pem 二选一（生产用 JWKS，测试可用 PEM）。
 */
@ConfigurationProperties("cs.auth.casdoor")
public record CasdoorAuthProperties(
        String issuer,
        String jwkSetUri,
        String publicKeyPem) {

    public CasdoorAuthProperties {
        if (issuer == null || issuer.isBlank()) {
            issuer = "urn:casdoor:default";
        }
    }

    public boolean configured() {
        return (jwkSetUri != null && !jwkSetUri.isBlank())
                || (publicKeyPem != null && !publicKeyPem.isBlank());
    }
}

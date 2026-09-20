package com.enterprise.cs.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INF-2 回传核验后的转换器单测（《11》§7 v1.3.0，坑#19）：
 * Casdoor 实测 roles 为对象数组（角色名在 name），转换须产出 ROLE_AGENT 等；
 * 访客 scope=cs.webchat → ROLE_VISITOR。纯单测，不起容器。
 */
class AuthorityConverterTest {

    private final JwtAuthenticationConverter converter = new SecurityConfig().authorityConverter();

    private Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claims(c -> c.putAll(claims))
                .build();
    }

    private List<String> authorities(Map<String, Object> claims) {
        var token = (JwtAuthenticationToken) converter.convert(jwt(claims));
        assertThat(token).isNotNull();
        // Framework 7 / Security 7：bearer 认证默认追加 FACTOR_BEARER authority（认证因子模型），
        // 不参与角色面；此处只断言 ROLE_*（hasRole 授权面）。
        return token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .toList();
    }

    /**
     * 镜像 INF-2 回传实测 payload：顶层 name=用户名（agent_001），roles[] 元素内 name=角色名（AGENT）。
     * 断言只取后者——若误用顶层 name 会产出 ROLE_agent_001，此处立即失败。
     */
    @Test
    void casdoorRolesAsObjectArrayExtractName() {
        assertThat(authorities(Map.of(
                "iss", "https://auth.example",
                "sub", "3f7a4a56-eeae-4d95-9805-3a59f7e39185",
                "name", "agent_001",
                "owner", "csca_tenant_001",
                "scope", "read",
                "roles", List.of(Map.of("name", "AGENT", "owner", "csca_tenant_001")))))
                .containsExactly("ROLE_AGENT");
    }

    @Test
    void stringRolesRemainSupported() {
        assertThat(authorities(Map.of("scope", "read", "roles", List.of("ADMIN", "AGENT"))))
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_AGENT");
    }

    @Test
    void visitorScopeGrantsVisitorRoleOnly() {
        assertThat(authorities(Map.of("scope", "cs.webchat")))
                .containsExactly("ROLE_VISITOR");
    }

    @Test
    void unparsableRolesYieldNoRoleAuthorities() {
        assertThat(authorities(Map.of(
                "scope", "read",
                "roles", List.of(Map.of("name", "")))))
                .isEmpty();
    }
}

package com.enterprise.cs.api.security;

import com.enterprise.cs.infra.auth.CasdoorAuthProperties;
import com.enterprise.cs.infra.auth.PemReader;
import com.enterprise.cs.infra.auth.VisitorKeyConfig.VisitorKeys;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.web.SecurityFilterChain;

import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 资源服务器安全配置（《11》§7 v1.2.0）：双 issuer——
 * ① Casdoor（坐席/服务/管理员，JWKS 或 PEM）；② 平台自签访客（RSA 公钥）。
 * 授权面：/api/v1/chat/** 需 ROLE_VISITOR（scope=cs.webchat）；Casdoor roles → ROLE_*；
 * 访客令牌换发端点 permitAll（自带 appKey+HMAC 验签）。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           CasdoorAuthProperties casdoor,
                                           VisitorKeys visitorKeys) throws Exception {
        Map<String, AuthenticationManager> issuers = new HashMap<>();
        issuers.put(visitorKeys.issuer(), manager(visitorDecoder(visitorKeys)));
        if (casdoor.configured()) {
            issuers.put(casdoor.issuer(), manager(casdoorDecoder(casdoor)));
        }
        // Security 7 无 Map 构造形态（源码核验）：经 resolver 委托按 issuer 路由
        JwtIssuerAuthenticationManagerResolver resolver = new JwtIssuerAuthenticationManagerResolver(
                issuer -> {
                    AuthenticationManager m = issuers.get(issuer);
                    if (m == null) {
                        throw new BadCredentialsException("unknown issuer: " + issuer);
                    }
                    return m;
                });

        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/widget/**", "/actuator/health", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/chat/visitor-tokens").permitAll()
                        .requestMatchers("/api/v1/chat/**").hasRole("VISITOR")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.authenticationManagerResolver(resolver));
        return http.build();
    }

    private JwtDecoder visitorDecoder(VisitorKeys keys) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withPublicKey((RSAPublicKey) keys.publicKey())
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(keys.issuer()));
        return decoder;
    }

    private JwtDecoder casdoorDecoder(CasdoorAuthProperties casdoor) {
        NimbusJwtDecoder decoder;
        if (casdoor.jwkSetUri() != null && !casdoor.jwkSetUri().isBlank()) {
            decoder = NimbusJwtDecoder.withJwkSetUri(casdoor.jwkSetUri()).build();
        } else {
            decoder = NimbusJwtDecoder
                    .withPublicKey((RSAPublicKey) PemReader.readPublicKey(casdoor.publicKeyPem()))
                    .build();
        }
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(casdoor.issuer()));
        return decoder;
    }

    private AuthenticationManager manager(JwtDecoder decoder) {
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(authorityConverter());
        return new ProviderManager(provider);
    }

    /** scope=cs.webchat → ROLE_VISITOR；Casdoor roles（集合或单值）→ ROLE_*。 */
    private JwtAuthenticationConverter authorityConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var authorities = new ArrayList<GrantedAuthority>();
            String scope = jwt.getClaimAsString("scope");
            if (scope != null && scope.contains("cs.webchat")) {
                authorities.add(new SimpleGrantedAuthority("ROLE_VISITOR"));
            }
            Object roles = jwt.getClaims().get("roles");
            if (roles instanceof Iterable<?> collection) {
                collection.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
            } else if (roles instanceof String role) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
            return authorities;
        });
        return converter;
    }
}

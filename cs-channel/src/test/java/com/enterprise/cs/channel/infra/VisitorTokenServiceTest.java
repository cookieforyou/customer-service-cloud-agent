package com.enterprise.cs.channel.infra;

import com.enterprise.cs.channel.api.ChatDtos.VisitorTokenRequest;
import com.enterprise.cs.channel.api.ChatDtos.VisitorTokenResponse;
import com.enterprise.cs.commons.exception.BusinessException;
import com.enterprise.cs.infra.auth.VisitorAuthProperties;
import com.enterprise.cs.infra.auth.VisitorKeyConfig.VisitorKeys;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** M0批3：访客令牌签发（验签窗口/HMAC/claims）单元测试。 */
class VisitorTokenServiceTest {

    private final KeyPair pair = rsa();
    private final VisitorKeys keys =
            new VisitorKeys("urn:test:visitor", pair.getPublic(), pair.getPrivate(), false);
    private final VisitorTokenService service = new VisitorTokenService(
            new WebchatAppsProperties(
                    java.util.List.of(new WebchatAppsProperties.WebchatApp("demo-app", "demo-secret", "demo-tenant")),
                    Duration.ofMinutes(5)),
            keys,
            new VisitorAuthProperties("urn:test:visitor", null, null, Duration.ofHours(1)));

    @Test
    void issuesTokenWithContractClaims() throws Exception {
        long ts = System.currentTimeMillis();
        VisitorTokenResponse resp = service.issue(new VisitorTokenRequest(
                "demo-app", ts, VisitorTokenService.hmacSha256("demo-secret", "demo-app\n" + ts), "v-1"));

        SignedJWT jwt = SignedJWT.parse(resp.token());
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("urn:test:visitor");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("v-1");
        assertThat(jwt.getJWTClaimsSet().getClaim("owner")).isEqualTo("demo-tenant");
        assertThat(jwt.getJWTClaimsSet().getClaim("scope")).isEqualTo("cs.webchat");
        assertThat(resp.visitorId()).isEqualTo("v-1");
    }

    @Test
    void generatesVisitorIdWhenAbsent() {
        long ts = System.currentTimeMillis();
        VisitorTokenResponse resp = service.issue(new VisitorTokenRequest(
                "demo-app", ts, VisitorTokenService.hmacSha256("demo-secret", "demo-app\n" + ts), null));
        assertThat(resp.visitorId()).isNotBlank();
    }

    @Test
    void rejectsUnknownAppKey() {
        long ts = System.currentTimeMillis();
        assertThatThrownBy(() -> service.issue(new VisitorTokenRequest("nope", ts, "x", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("appKey");
    }

    @Test
    void rejectsStaleTimestamp() {
        long stale = System.currentTimeMillis() - Duration.ofMinutes(10).toMillis();
        String sign = VisitorTokenService.hmacSha256("demo-secret", "demo-app\n" + stale);
        assertThatThrownBy(() -> service.issue(new VisitorTokenRequest("demo-app", stale, sign, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("时间戳");
    }

    @Test
    void rejectsBadSign() {
        long ts = System.currentTimeMillis();
        String wrong = VisitorTokenService.hmacSha256("other-secret", "demo-app\n" + ts);
        assertThatThrownBy(() -> service.issue(new VisitorTokenRequest("demo-app", ts, wrong, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("签名不匹配");
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

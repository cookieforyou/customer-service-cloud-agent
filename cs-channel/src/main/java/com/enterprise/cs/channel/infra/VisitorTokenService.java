package com.enterprise.cs.channel.infra;

import com.enterprise.cs.channel.api.ChatDtos.VisitorTokenRequest;
import com.enterprise.cs.channel.api.ChatDtos.VisitorTokenResponse;
import com.enterprise.cs.commons.constant.ErrorCodes;
import com.enterprise.cs.commons.exception.BusinessException;
import com.enterprise.cs.infra.auth.VisitorAuthProperties;
import com.enterprise.cs.infra.auth.VisitorKeyConfig.VisitorKeys;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * 访客令牌签发（《08》§3 /《11》§7 v1.2.0）：appKey+时间戳+HMAC-SHA256 验签 →
 * 平台自签 RS256 JWT（iss=访签 issuer，sub=visitorId，owner=站点租户，scope=cs.webchat）。
 * 时间窗 ±sign-window；HMAC 比较用常量时间（MessageDigest.isEqual）。
 */
@Component
public class VisitorTokenService {

    public static final String SCOPE_WEBCHAT = "cs.webchat";

    private final WebchatAppsProperties apps;
    private final VisitorKeys keys;
    private final VisitorAuthProperties authProps;

    public VisitorTokenService(WebchatAppsProperties apps, VisitorKeys keys, VisitorAuthProperties authProps) {
        this.apps = apps;
        this.keys = keys;
        this.authProps = authProps;
    }

    public VisitorTokenResponse issue(VisitorTokenRequest request) {
        if (request == null || request.appKey() == null || request.sign() == null) {
            throw BusinessException.of(ErrorCodes.BAD_REQUEST, "appKey/sign/timestamp 必填");
        }
        WebchatAppsProperties.WebchatApp app = apps.find(request.appKey())
                .orElseThrow(() -> BusinessException.of(ErrorCodes.APP_KEY_NOT_FOUND, "appKey 未登记"));

        long skew = apps.signWindow().toMillis();
        if (Math.abs(System.currentTimeMillis() - request.timestamp()) > skew) {
            throw BusinessException.of(ErrorCodes.SIGN_EXPIRED, "签名时间戳超出窗口 ±" + apps.signWindow());
        }
        String expected = hmacSha256(app.secret(), request.appKey() + "\n" + request.timestamp());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                request.sign().getBytes(StandardCharsets.UTF_8))) {
            throw BusinessException.of(ErrorCodes.INVALID_SIGN, "签名不匹配");
        }

        String visitorId = (request.visitorId() == null || request.visitorId().isBlank())
                ? UUID.randomUUID().toString() : request.visitorId();
        Instant now = Instant.now();
        Instant exp = now.plus(authProps.ttl());
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(keys.issuer())
                    .subject(visitorId)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(exp))
                    .claim("owner", app.tenantId())
                    .claim("scope", SCOPE_WEBCHAT)
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(keys.privateKey()));
            return new VisitorTokenResponse(jwt.serialize(), visitorId, authProps.ttl().toSeconds());
        } catch (Exception e) {
            throw new IllegalStateException("visitor token signing failed", e);
        }
    }

    static String hmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("hmac failed", e);
        }
    }
}

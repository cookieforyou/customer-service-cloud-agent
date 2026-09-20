package com.enterprise.cs;

import com.enterprise.cs.commons.constant.RedisKeys;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M0批3 webchat 全链集成测试（真实 PG + Redis Stack 容器，RANDOM_PORT）：
 * ① 访客令牌验签（坏签名/过期时间戳拒绝）；② 授权面（无 token 401 / Casdoor 坐席 403 / 访客 200）；
 * ③ 消息→ACK→SSE 帧（TOKEN×N+DONE，序号单调）；④ 幂等双路径（Redis 前置 + DB 唯一键兜底）；
 * ⑤ Last-Event-ID 缓冲补发；⑥ 访客限流 429。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ChatFlowIntegrationTest {

    private static final String APP_KEY = "demo-app";
    private static final String APP_SECRET = "demo-secret";
    private static final String TENANT = "demo-tenant";
    private static final String CASDOOR_ISSUER = "urn:test:casdoor";

    @Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis/redis-stack-server:7.4.0-v0"))
                    .withExposedPorts(6379);

    static final KeyPair VISITOR = rsa();
    static final KeyPair CASDOOR = rsa();
    static final JsonMapper JSON = new JsonMapper();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> String.valueOf(REDIS.getMappedPort(6379)));
        r.add("cs.auth.visitor.public-key-pem", () -> pem(VISITOR.getPublic(), "PUBLIC KEY"));
        r.add("cs.auth.visitor.private-key-pem", () -> pem(VISITOR.getPrivate(), "PRIVATE KEY"));
        r.add("cs.auth.casdoor.issuer", () -> CASDOOR_ISSUER);
        r.add("cs.auth.casdoor.public-key-pem", () -> pem(CASDOOR.getPublic(), "PUBLIC KEY"));
        r.add("cs.channel.limits.visitor-rpm", () -> "3");
    }

    @LocalServerPort
    int port;

    WebClient web;

    @Autowired
    RedissonClient redisson;

    @Autowired
    com.enterprise.cs.conversation.api.ConversationPort conversation;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    WebClient client() {
        if (web == null) {
            web = WebClient.builder().baseUrl("http://localhost:" + port).build();
        }
        return web;
    }

    // ---------- ① 访客令牌验签 ----------

    @Test
    void visitorTokenRejectsBadSignAndStaleTimestamp() {
        long ts = System.currentTimeMillis();
        Resp badSign = post("/api/v1/chat/visitor-tokens", null,
                Map.of("appKey", APP_KEY, "timestamp", ts, "sign", "deadbeef"));
        assertThat(badSign.status()).isEqualTo(401);
        assertThat(badSign.body().path("code").asText()).isEqualTo("INVALID_SIGN");

        long stale = ts - Duration.ofMinutes(10).toMillis();
        Resp staleResp = post("/api/v1/chat/visitor-tokens", null,
                Map.of("appKey", APP_KEY, "timestamp", stale, "sign", hmac(APP_KEY, stale)));
        assertThat(staleResp.status()).isEqualTo(401);
        assertThat(staleResp.body().path("code").asText()).isEqualTo("SIGN_EXPIRED");
    }

    // ---------- ② 授权面 ----------

    @Test
    void chatRequiresVisitorScope() throws Exception {
        assertThat(post("/api/v1/chat/sessions", null, Map.of()).status()).isEqualTo(401);

        Resp agent = post("/api/v1/chat/sessions", casdoorAgentToken(), Map.of());
        assertThat(agent.status()).isEqualTo(403);  // 坐席角色无 cs.webchat scope

        Resp visitor = post("/api/v1/chat/sessions", visitorToken("v-auth"), Map.of());
        assertThat(visitor.status()).isEqualTo(200);
        assertThat(visitor.body().path("data").path("sessionId").asText()).isNotBlank();
    }

    // ---------- ③ 全链：消息 → ACK → SSE 帧 ----------

    @Test
    void fullTurnEmitsTokenAndDoneFrames() {
        String token = visitorToken("v-1");
        UUID sessionId = openSession(token);
        Resp ack = post("/api/v1/chat/sessions/" + sessionId + "/messages", token,
                Map.of("text", "退款怎么办理", "channelMsgId", "m-1"));
        assertThat(ack.status()).as("body=%s", ack.body()).isEqualTo(200);
        assertThat(ack.body().path("data").path("duplicate").asBoolean()).isFalse();
        String turnId = ack.body().path("data").path("turnId").asText();
        String messageId = ack.body().path("data").path("messageId").asText();
        assertThat(turnId).isNotBlank();

        List<ServerSentEvent<String>> frames = readStream(token, sessionId, null);
        assertThat(frames).isNotEmpty();
        assertThat(frames.stream().filter(f -> "TOKEN".equals(f.event())).count()).isGreaterThanOrEqualTo(2);
        ServerSentEvent<String> done = frames.get(frames.size() - 1);
        assertThat(done.event()).isEqualTo("DONE");
        assertThat(done.data()).contains(turnId).contains(messageId);
        assertThat(frames).allSatisfy(f -> assertThat(f.id()).isNotNull());
    }

    // ---------- ④ 幂等：Redis 前置 + DB 唯一键兜底 ----------

    @Test
    void idempotentReplayBothPaths() {
        String token = visitorToken("v-2");
        UUID sessionId = openSession(token);
        Map<String, Object> body = Map.of("text", "重复发送同一条", "channelMsgId", "m-dup");

        Resp first = post("/api/v1/chat/sessions/" + sessionId + "/messages", token, body);
        assertThat(first.body().path("data").path("duplicate").asBoolean()).isFalse();

        // 路径一：Redis SETNX/回执缓存
        Resp second = post("/api/v1/chat/sessions/" + sessionId + "/messages", token, body);
        assertThat(second.body().path("data").path("duplicate").asBoolean()).isTrue();
        assertThat(second.body().path("data").path("messageId").asText())
                .isEqualTo(first.body().path("data").path("messageId").asText());

        // 路径二：清除 Redis 痕迹后走 DB 唯一键兜底
        String idemKey = RedisKeys.idem(TENANT, "webchat", "m-dup");
        redisson.getBucket(idemKey).delete();
        redisson.getBucket(idemKey + ":ack").delete();
        Resp third = post("/api/v1/chat/sessions/" + sessionId + "/messages", token, body);
        assertThat(third.body().path("data").path("duplicate").asBoolean()).isTrue();
        assertThat(third.body().path("data").path("messageId").asText())
                .isEqualTo(first.body().path("data").path("messageId").asText());
    }

    // ---------- ⑤ Last-Event-ID 缓冲补发 ----------

    @Test
    void lastEventIdReplaysBufferedFramesOnly() {
        String token = visitorToken("v-3");
        UUID sessionId = openSession(token);
        post("/api/v1/chat/sessions/" + sessionId + "/messages", token,
                Map.of("text", "断线补发测试消息", "channelMsgId", "m-replay"));
        List<ServerSentEvent<String>> full = readStream(token, sessionId, null);  // 等待轮次完成
        assertThat(full.size()).isGreaterThan(2);

        List<ServerSentEvent<String>> partial = readStream(token, sessionId, 2L);
        assertThat(partial).isNotEmpty();
        assertThat(partial).allSatisfy(f -> assertThat(Long.parseLong(f.id())).isGreaterThan(2));
        assertThat(partial.get(partial.size() - 1).event()).isEqualTo("DONE");
    }

    // ---------- ⑥ 访客限流 ----------

    @Test
    void rateLimitRejectsBeyondRpm() {
        String token = visitorToken("v-rl");
        UUID sessionId = openSession(token);
        AtomicInteger seq = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            Resp ok = post("/api/v1/chat/sessions/" + sessionId + "/messages", token,
                    Map.of("text", "msg", "channelMsgId", "m-rl-" + seq.incrementAndGet()));
            assertThat(ok.status()).isEqualTo(200);
        }
        Resp limited = post("/api/v1/chat/sessions/" + sessionId + "/messages", token,
                Map.of("text", "msg", "channelMsgId", "m-rl-" + seq.incrementAndGet()));
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.body().path("code").asText()).isEqualTo("RATE_LIMITED");
    }

    // ---------- helpers ----------

    private String visitorToken(String visitorId) {
        long ts = System.currentTimeMillis();
        Resp resp = post("/api/v1/chat/visitor-tokens", null,
                Map.of("appKey", APP_KEY, "timestamp", ts, "sign", hmac(APP_KEY, ts), "visitorId", visitorId));
        assertThat(resp.status()).isEqualTo(200);
        return resp.body().path("data").path("token").asText();
    }

    private String casdoorAgentToken() throws Exception {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                .issuer(CASDOOR_ISSUER).subject("agent-1")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .claim("owner", TENANT).claim("roles", List.of("AGENT"))
                .build());
        jwt.sign(new RSASSASigner(CASDOOR.getPrivate()));
        return jwt.serialize();
    }

    private UUID openSession(String token) {
        Resp resp = post("/api/v1/chat/sessions", token, Map.of());
        assertThat(resp.status()).isEqualTo(200);
        return UUID.fromString(resp.body().path("data").path("sessionId").asText());
    }

    private List<ServerSentEvent<String>> readStream(String token, UUID sessionId, Long afterId) {
        String uri = "/api/v1/chat/sessions/" + sessionId + "/stream"
                + (afterId != null ? "?lastEventId=" + afterId : "");
        return client().get().uri(uri)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .collectList()
                .block(Duration.ofSeconds(15));
    }

    private Resp post(String path, String token, Object body) {
        return client().post().uri(path)
                .headers(h -> {
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(r -> r.bodyToMono(String.class).defaultIfEmpty("")
                        .map(s -> new Resp(r.statusCode().value(), parse(s))))
                .block(Duration.ofSeconds(15));
    }

    private JsonNode parse(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            return JSON.readTree("{}");
        }
    }

    private static String hmac(String appKey, long ts) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal((appKey + "\n" + ts).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : raw) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String pem(java.security.Key key, String type) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded());
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }

    static KeyPair rsa() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    record Resp(int status, JsonNode body) {
    }
}

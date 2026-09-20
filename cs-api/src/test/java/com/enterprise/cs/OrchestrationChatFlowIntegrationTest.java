package com.enterprise.cs;

import com.enterprise.cs.ai.api.RoutingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * M0批4 最小对话链集成测试（真实 PG + Redis 容器 + orchestration 引擎，模型 mock）：
 * ① 消息→TOKEN×N+DONE 全链；② turn/message/event 落库（cs_turn 计量回填、cs_message AI 落档与
 * turn_id 回填、cs_session_event ROUTE_DECIDED+MESSAGE_APPENDED）；③ 第二轮 prompt 含窗口历史
 * （SessionMemoryAdvisor 集成面）；④ 模型故障路径 FAILED 落库 + ERROR 帧。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "cs.channel.turn-engine=orchestration")
@Testcontainers
class OrchestrationChatFlowIntegrationTest {

    private static final String APP_KEY = "demo-app";
    private static final String APP_SECRET = "demo-secret";
    private static final String TENANT = "demo-tenant";

    @Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis/redis-stack-server:7.4.0-v0"))
                    .withExposedPorts(6379);

    static final KeyPair VISITOR = rsa();
    static final JsonMapper JSON = new JsonMapper();

    /** cs.ai.enabled 缺省 false——真模型 Bean 不装配，mock 路由模型即可承载 supervisor 链。 */
    @MockitoBean
    RoutingChatModel routingChatModel;

    @Autowired
    JdbcTemplate jdbc;

    @LocalServerPort
    int port;

    WebClient web;

    final List<Prompt> modelPrompts = new ArrayList<>();

    WebClient client() {
        if (web == null) {
            web = WebClient.builder().baseUrl("http://localhost:" + port).build();
        }
        return web;
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> String.valueOf(REDIS.getMappedPort(6379)));
        r.add("cs.auth.visitor.public-key-pem", () -> pem(VISITOR.getPublic(), "PUBLIC KEY"));
        r.add("cs.auth.visitor.private-key-pem", () -> pem(VISITOR.getPrivate(), "PRIVATE KEY"));
    }

    @BeforeEach
    void stubModel() {
        when(routingChatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        when(routingChatModel.stream(any(Prompt.class))).thenAnswer(inv -> {
            modelPrompts.add(inv.getArgument(0, Prompt.class));
            return Flux.just(chunk("退款会在"), chunk("1-3个工作日原路退回"), usageOnly());
        });
    }

    // ---------- ① 全链 + ② 落库 ----------

    @Test
    void fullTurnStreamsAndPersists() throws Exception {
        String token = visitorToken("v-1");
        UUID sessionId = openSession(token);
        Resp[] ackRef = new Resp[1];
        List<ServerSentEvent<String>> frames = framesOf(token, sessionId, 0L, () ->
                ackRef[0] = post("/api/v1/chat/sessions/" + sessionId + "/messages", token,
                        Map.of("text", "退款怎么办理", "channelMsgId", "m-1")));
        Resp ack = ackRef[0];
        assertThat(ack.status()).as("body=%s", ack.body()).isEqualTo(200);
        UUID turnId = UUID.fromString(ack.body().path("data").path("turnId").asText());
        UUID messageId = UUID.fromString(ack.body().path("data").path("messageId").asText());

        assertThat(frames.stream().filter(f -> "TOKEN".equals(f.event())).count()).isGreaterThanOrEqualTo(2);
        assertThat(frames.get(frames.size() - 1).event()).isEqualTo("DONE");
        assertThat(frames.get(frames.size() - 1).data()).contains(turnId.toString()).contains(messageId.toString());

        assertThat(await(() -> jdbc.queryForObject(
                "select state from cs_turn where id = ?", String.class, turnId)))
                .isEqualTo("COMPLETED");
        Map<String, Object> turn = jdbc.queryForMap(
                "select model_tier, tokens_in, tokens_out, latency_ms from cs_turn where id = ?", turnId);
        assertThat(turn.get("model_tier")).isEqualTo("T1");
        assertThat(turn.get("tokens_in")).isEqualTo(11);
        assertThat(turn.get("tokens_out")).isEqualTo(7);
        assertThat(turn.get("latency_ms")).isNotNull();

        Map<String, Object> userMsg = jdbc.queryForMap(
                "select turn_id from cs_message where id = ?", messageId);
        assertThat(userMsg.get("turn_id")).isEqualTo(turnId);

        assertThat(await(() -> jdbc.queryForObject(
                "select count(*) from cs_message where session_id = ? and role = 'AI' and turn_id = ? "
                        + "and content = '退款会在1-3个工作日原路退回'",
                Integer.class, sessionId, turnId))).isEqualTo(1);

        List<String> eventTypes = await(() -> jdbc.queryForList(
                "select event_type from cs_session_event where session_id = ? order by seq",
                String.class, sessionId));
        assertThat(eventTypes).contains("ROUTE_DECIDED", "MESSAGE_APPENDED");
    }

    // ---------- ③ 窗口历史进第二轮 prompt ----------

    @Test
    void secondTurnPromptContainsWindowHistory() throws Exception {
        String token = visitorToken("v-2");
        UUID sessionId = openSession(token);
        Resp[] ack1 = new Resp[1];
        List<ServerSentEvent<String>> frames1 = framesOf(token, sessionId, 0L, () ->
                ack1[0] = post("/api/v1/chat/sessions/" + sessionId + "/messages", token,
                        Map.of("text", "退款怎么办理", "channelMsgId", "m-h1")));
        long afterTurn1 = Long.parseLong(frames1.get(frames1.size() - 1).id());
        framesOf(token, sessionId, afterTurn1, () ->
                send(token, sessionId, "多久能到账", "m-h2"));

        assertThat(awaitEquals(2, () -> jdbc.queryForObject(
                "select count(*) from cs_turn where session_id = ? and state = 'COMPLETED'",
                Integer.class, sessionId))).isEqualTo(2);

        assertThat(modelPrompts).hasSize(2);
        Prompt second = modelPrompts.get(1);
        List<String> texts = second.getInstructions().stream()
                .map(m -> m.getText() == null ? "" : m.getText()).toList();
        // 系统提示在首（supervisor 代码内 prompt）；历史窗口（用户+AI）+ 本轮输入在尾
        assertThat(texts.get(0)).contains("对话主管");
        assertThat(texts).contains("退款怎么办理", "退款会在1-3个工作日原路退回");
        assertThat(texts.get(texts.size() - 1)).isEqualTo("多久能到账");

        Prompt first = modelPrompts.get(0);
        List<String> firstTexts = first.getInstructions().stream()
                .map(m -> m.getText() == null ? "" : m.getText()).toList();
        // 首轮 = [系统提示, 本轮输入]——无历史窗口
        assertThat(firstTexts).hasSize(2);
        assertThat(firstTexts.get(0)).contains("对话主管");
        assertThat(firstTexts.get(1)).isEqualTo("退款怎么办理");
    }

    // ---------- ④ 模型故障：FAILED 落库 + ERROR 帧 ----------

    @Test
    void modelFailureMarksTurnFailedAndEmitsErrorFrame() throws Exception {
        when(routingChatModel.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("boom")));
        String token = visitorToken("v-3");
        UUID sessionId = openSession(token);
        Resp[] ackRef = new Resp[1];
        List<ServerSentEvent<String>> frames = framesOf(token, sessionId, 0L, () ->
                ackRef[0] = post("/api/v1/chat/sessions/" + sessionId + "/messages", token,
                        Map.of("text", "模型故障路径", "channelMsgId", "m-e1")));
        UUID turnId = UUID.fromString(ackRef[0].body().path("data").path("turnId").asText());

        assertThat(frames.get(frames.size() - 1).event()).isEqualTo("ERROR");
        assertThat(await(() -> jdbc.queryForObject(
                "select state from cs_turn where id = ?", String.class, turnId))).isEqualTo("FAILED");
    }

    // ---------- helpers ----------

    /**
     * 独立线程先订阅 SSE（与 Widget/EventSource 真实行为一致——DONE 即完成流，多轮经重连续接），
     * 300ms 后触发发送，按完成帧（DONE/ERROR）截断收集。afterId 即 Last-Event-ID（多轮续接）。
     */
    private List<ServerSentEvent<String>> framesOf(String token, UUID sessionId, long afterId,
                                                   Runnable trigger) throws Exception {
        java.util.concurrent.CompletableFuture<List<ServerSentEvent<String>>> collected =
                new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        Thread collector = new Thread(() -> {
            try {
                List<ServerSentEvent<String>> frames = client().get()
                        .uri("/api/v1/chat/sessions/" + sessionId + "/stream?lastEventId=" + afterId)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .headers(h -> h.setBearerAuth(token))
                        .retrieve()
                        .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                        })
                        .takeUntil(f -> {
                            if ("DONE".equals(f.event()) || "ERROR".equals(f.event())) {
                                return finished.compareAndSet(false, true);
                            }
                            return false;
                        })
                        .collectList()
                        .block(Duration.ofSeconds(20));
                collected.complete(frames);
            } catch (RuntimeException e) {
                collected.completeExceptionally(e);
            }
        }, "sse-collector");
        collector.start();
        Thread.sleep(300);
        trigger.run();
        return collected.get(25, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void send(String token, UUID sessionId, String text, String channelMsgId) {
        Resp ack = post("/api/v1/chat/sessions/" + sessionId + "/messages", token,
                Map.of("text", text, "channelMsgId", channelMsgId));
        assertThat(ack.status()).as("body=%s", ack.body()).isEqualTo(200);
        assertThat(ack.body().path("data").path("duplicate").asBoolean())
                .as("body=%s", ack.body()).isFalse();
        assertThat(ack.body().path("data").path("turnId").asText()).as("body=%s", ack.body()).isNotBlank();
    }

    /** 轮询直至值等于期望（多轮异步完成场景；返回最终值供断言）。 */
    private <T> T awaitEquals(T expected, java.util.function.Supplier<T> poll) {
        Object last = null;
        for (int i = 0; i < 150; i++) {
            try {
                last = poll.get();
                if (expected.equals(last)) {
                    return expected;
                }
            } catch (RuntimeException ignored) {
                // 行未就绪继续轮询
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return (T) last;
    }

    private <T> T await(java.util.function.Supplier<T> poll) {
        for (int i = 0; i < 100; i++) {
            try {
                T v = poll.get();
                if (v instanceof Integer n && n == 0) {
                    // count=0 继续轮询
                } else if (v instanceof List<?> l && l.isEmpty()) {
                    // 空列表继续轮询
                } else {
                    return v;
                }
            } catch (RuntimeException ignored) {
                // 行未就绪（EmptyResultDataAccessException 等）继续轮询
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return poll.get();   // 最后一次直取，让断言拿到真实值/异常
    }

    private String visitorToken(String visitorId) {
        long ts = System.currentTimeMillis();
        Resp resp = post("/api/v1/chat/visitor-tokens", null,
                Map.of("appKey", APP_KEY, "timestamp", ts, "sign", hmac(APP_KEY, ts), "visitorId", visitorId));
        assertThat(resp.status()).isEqualTo(200);
        return resp.body().path("data").path("token").asText();
    }

    private UUID openSession(String token) {
        Resp resp = post("/api/v1/chat/sessions", token, Map.of());
        assertThat(resp.status()).isEqualTo(200);
        return UUID.fromString(resp.body().path("data").path("sessionId").asText());
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

    static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    static ChatResponse usageOnly() {
        return ChatResponse.builder()
                .generations(List.of())
                .metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(11, 7)).build())
                .build();
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

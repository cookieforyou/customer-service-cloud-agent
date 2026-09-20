package com.enterprise.cs.channel.app;

import com.enterprise.cs.channel.api.AuthClaims;
import com.enterprise.cs.channel.api.ChatDtos.SendMessageRequest;
import com.enterprise.cs.channel.api.ChatDtos.SendMessageView;
import com.enterprise.cs.channel.api.ChatDtos.SessionOpenedView;
import com.enterprise.cs.channel.api.ChatDtos.VisitorTokenRequest;
import com.enterprise.cs.channel.api.ChatDtos.VisitorTokenResponse;
import com.enterprise.cs.channel.api.ChatTurnPort;
import com.enterprise.cs.channel.api.FrameSink;
import com.enterprise.cs.channel.infra.IdempotencyService;
import com.enterprise.cs.channel.infra.SessionFrameBus;
import com.enterprise.cs.channel.infra.VisitorRateLimiter;
import com.enterprise.cs.channel.infra.VisitorTokenService;
import com.enterprise.cs.commons.constant.ErrorCodes;
import com.enterprise.cs.commons.constant.RedisKeys;
import com.enterprise.cs.commons.exception.BusinessException;
import com.enterprise.cs.conversation.api.ConversationPort;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * webchat 入站编排（《08》§3 管线：限流→幂等→会话归属校验→持久化→异步起轮→回执）。
 * 阶段：M0批3；轮次引擎为 echo 桩（M0批4 换 orchestration 实现）。
 */
@Service
public class ChatService {

    private static final String CHANNEL = "webchat";

    /** 轮次执行线程（JDK 25 虚拟线程；M0批4 起归 orchestration 正式执行器接管）。 */
    private static final ExecutorService TURN_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ConversationPort conversation;
    private final VisitorTokenService visitorTokens;
    private final IdempotencyService idempotency;
    private final VisitorRateLimiter rateLimiter;
    private final SessionFrameBus frames;
    private final ChatTurnPort turnPort;

    public ChatService(ConversationPort conversation, VisitorTokenService visitorTokens,
                       IdempotencyService idempotency, VisitorRateLimiter rateLimiter,
                       SessionFrameBus frames, ChatTurnPort turnPort) {
        this.conversation = conversation;
        this.visitorTokens = visitorTokens;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
        this.frames = frames;
        this.turnPort = turnPort;
    }

    public VisitorTokenResponse issueVisitorToken(VisitorTokenRequest request) {
        return visitorTokens.issue(request);
    }

    public SessionOpenedView openSession(AuthClaims claims) {
        ConversationPort.SessionOpened opened =
                conversation.openSession(new ConversationPort.OpenSessionCmd(claims.tenantId(), CHANNEL, claims.principalId()));
        return new SessionOpenedView(opened.sessionId());
    }

    public SendMessageView sendMessage(UUID sessionId, SendMessageRequest request, AuthClaims claims) {
        if (request == null || request.text() == null || request.text().isBlank()
                || request.channelMsgId() == null || request.channelMsgId().isBlank()) {
            throw BusinessException.of(ErrorCodes.BAD_REQUEST, "text 与 channelMsgId 必填");
        }
        rateLimiter.check(claims.principalId());

        // 归属校验：会话不存在或非本人/租户，一律 404（不泄露存在性，《11》§6）
        ConversationPort.SessionInfo info = requireOwnedSession(sessionId, claims);

        // 幂等：Redis SETNX 前置 + DB 唯一键兜底（《03》§5）
        String idemKey = RedisKeys.idem(claims.tenantId(), CHANNEL, request.channelMsgId());
        Optional<IdempotencyService.Ack> existing = idempotency.acquire(idemKey);
        if (existing.isPresent()) {
            return new SendMessageView(existing.get().turnId(), existing.get().messageId(), true);
        }

        // DB 唯一键兜底：冲突事务已回滚，补偿读经独立新事务（《03》§5 双闸）
        ConversationPort.AppendedMessage appended;
        try {
            appended = conversation.appendInbound(new ConversationPort.AppendInboundCmd(
                    sessionId, claims.tenantId(), CHANNEL, request.channelMsgId(), request.text()));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            UUID existingId = conversation.inboundMessageId(claims.tenantId(), CHANNEL, request.channelMsgId())
                    .orElseThrow(() -> e);
            return new SendMessageView(null, existingId, true);
        }
        if (appended.duplicate()) {
            return new SendMessageView(null, appended.messageId(), true);
        }

        UUID turnId = UUID.randomUUID();
        idempotency.store(idemKey, turnId, appended.messageId());
        FrameSink sink = frames.sinkFor(sessionId, turnId, appended.messageId());
        TURN_EXECUTOR.execute(() -> {
            try {
                turnPort.runTurn(new ChatTurnPort.TurnCommand(
                        sessionId, turnId, appended.messageId(), claims.tenantId(), request.text()), sink);
            } catch (Exception e) {
                sink.error(ErrorCodes.INTERNAL_ERROR, "turn failed");
            }
        });
        return new SendMessageView(turnId, appended.messageId(), false);
    }

    public Flux<ServerSentEvent<String>> stream(UUID sessionId, long lastEventId, AuthClaims claims) {
        requireOwnedSession(sessionId, claims);
        return frames.subscribe(sessionId, lastEventId);
    }

    private ConversationPort.SessionInfo requireOwnedSession(UUID sessionId, AuthClaims claims) {
        return conversation.sessionInfo(sessionId)
                .filter(s -> claims.tenantId().equals(s.tenantId())
                        && claims.principalId().equals(s.visitorId()))
                .orElseThrow(() -> BusinessException.of(ErrorCodes.SESSION_NOT_FOUND,
                        "session not found: " + sessionId));
    }
}

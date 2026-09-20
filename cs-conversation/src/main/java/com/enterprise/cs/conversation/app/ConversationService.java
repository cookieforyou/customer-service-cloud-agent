package com.enterprise.cs.conversation.app;

import com.enterprise.cs.commons.constant.CsConstants;
import com.enterprise.cs.commons.constant.ErrorCodes;
import com.enterprise.cs.commons.exception.BusinessException;
import com.enterprise.cs.conversation.api.ConversationPort;
import com.enterprise.cs.conversation.domain.Message;
import com.enterprise.cs.conversation.domain.MessageRepository;
import com.enterprise.cs.conversation.domain.Session;
import com.enterprise.cs.conversation.domain.SessionEvent;
import com.enterprise.cs.conversation.domain.SessionEventRepository;
import com.enterprise.cs.conversation.domain.SessionRepository;
import com.enterprise.cs.conversation.domain.Turn;
import com.enterprise.cs.conversation.domain.TurnRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话应用服务（《02》分层：app 为用例与事务边界）。
 * 阶段：M0批3 开态/入站消息最小用例；M0批4 增轮次生命周期（start/finish）与窗口读拼；
 * 状态机、三层记忆（摘要/槽位）于 M1批1 引入。
 */
@Service
@Transactional
public class ConversationService implements ConversationPort {

    private final SessionRepository sessions;
    private final MessageRepository messages;
    private final TurnRepository turns;
    private final SessionEventRepository events;

    public ConversationService(SessionRepository sessions, MessageRepository messages,
                               TurnRepository turns, SessionEventRepository events) {
        this.sessions = sessions;
        this.messages = messages;
        this.turns = turns;
        this.events = events;
    }

    @Override
    public SessionOpened openSession(OpenSessionCmd cmd) {
        UUID id = UUID.randomUUID();
        sessions.save(new Session(id, cmd.tenantId(), cmd.channel(), cmd.visitorId(), "CREATED", Instant.now()));
        return new SessionOpened(id);
    }

    @Override
    public Optional<SessionInfo> sessionInfo(UUID sessionId) {
        return sessions.findById(sessionId)
                .map(s -> new SessionInfo(s.getId(), s.getTenantId(), s.getChannel(), s.getVisitorId(), s.getState()));
    }

    @Override
    public AppendedMessage appendInbound(AppendInboundCmd cmd) {
        Session session = sessions.findById(cmd.sessionId())
                .orElseThrow(() -> BusinessException.of(ErrorCodes.SESSION_NOT_FOUND,
                        "session not found: " + cmd.sessionId()));
        if ("CREATED".equals(session.getState())) {
            session.setState("ACTIVE");
        }
        session.setLastActiveAt(Instant.now());
        int nextSeq = messages.findFirstBySessionIdOrderBySeqDesc(cmd.sessionId())
                .map(Message::getSeq).orElse(0) + 1;

        try {
            Message saved = messages.saveAndFlush(new Message(
                    UUID.randomUUID(), cmd.sessionId(), null, nextSeq,
                    "USER", "TEXT", cmd.content(),
                    cmd.tenantId(), cmd.channel(), cmd.channelMsgId(), Instant.now()));
            return new AppendedMessage(saved.getId(), false);
        } catch (DataIntegrityViolationException e) {
            // 冲突事务已回滚且被毒化（后续语句 25P02）——补偿读必须走调用方新事务
            // （ChatService 捕获后经 inboundMessageId 读取，《03》§5 双闸结构）
            throw e;
        }
    }

    @Override
    public Optional<UUID> inboundMessageId(String tenantId, String channel, String channelMsgId) {
        return messages.findByTenantIdAndChannelAndChannelMsgId(tenantId, channel, channelMsgId)
                .map(Message::getId);
    }

    @Override
    public void startTurn(StartTurnCmd cmd) {
        Session session = requireSession(cmd.sessionId());
        Turn turn = new Turn(cmd.turnId(), cmd.sessionId(), nextTurnSeq(cmd.sessionId()),
                CsConstants.TURN_STATE_RUNNING, Instant.now());
        turn.setModelTier(cmd.modelTier());
        turn.setRouteDecision(cmd.routeDecisionJson());
        turns.save(turn);
        messages.findById(cmd.inboundMessageId())
                .ifPresent(m -> m.setTurnId(cmd.turnId()));
        appendEvent(session, "ROUTE_DECIDED",
                "{\"turnId\":\"" + cmd.turnId() + "\",\"modelTier\":\"" + cmd.modelTier() + "\"}");
    }

    @Override
    public UUID finishTurn(FinishTurnCmd cmd) {
        Turn turn = turns.findById(cmd.turnId())
                .orElseThrow(() -> BusinessException.of(ErrorCodes.BAD_REQUEST,
                        "turn not found: " + cmd.turnId()));
        Session session = requireSession(turn.getSessionId());
        turn.setState(cmd.state());        turn.setTokensIn(cmd.tokensIn());
        turn.setTokensOut(cmd.tokensOut());
        turn.setLatencyMs(cmd.latencyMs());
        session.setLastActiveAt(Instant.now());

        Message assistant = messages.save(new Message(
                UUID.randomUUID(), turn.getSessionId(), turn.getId(), nextMessageSeq(turn.getSessionId()),
                "AI", "TEXT", cmd.assistantContent(),
                session.getTenantId(), session.getChannel(), null, Instant.now()));
        appendEvent(session, "MESSAGE_APPENDED",
                "{\"messageId\":\"" + assistant.getId() + "\",\"role\":\"AI\",\"turnId\":\"" + turn.getId() + "\"}");
        return assistant.getId();
    }

    @Override
    public List<WindowMessage> recentWindow(UUID sessionId, int limit, UUID excludeMessageId) {
        UUID exclude = excludeMessageId != null ? excludeMessageId : new UUID(0, 0);
        List<Message> recent = messages.findBySessionIdAndRoleInAndContentTypeAndIdNotOrderBySeqDesc(
                sessionId, List.of("USER", "AI"), "TEXT", exclude, Pageable.ofSize(Math.max(1, limit)));
        List<WindowMessage> window = new ArrayList<>(recent.size());
        for (int i = recent.size() - 1; i >= 0; i--) {
            Message m = recent.get(i);
            window.add(new WindowMessage(m.getRole(), m.getContent()));
        }
        return window;
    }

    private Session requireSession(UUID sessionId) {
        return sessions.findById(sessionId)
                .orElseThrow(() -> BusinessException.of(ErrorCodes.SESSION_NOT_FOUND,
                        "session not found: " + sessionId));
    }

    private int nextTurnSeq(UUID sessionId) {
        return turns.findFirstBySessionIdOrderBySeqDesc(sessionId).map(Turn::getSeq).orElse(0) + 1;
    }

    private int nextMessageSeq(UUID sessionId) {
        return messages.findFirstBySessionIdOrderBySeqDesc(sessionId).map(Message::getSeq).orElse(0) + 1;
    }

    private void appendEvent(Session session, String eventType, String payloadJson) {
        int seq = events.findFirstBySessionIdOrderBySeqDesc(session.getId())
                .map(SessionEvent::getSeq).orElse(0) + 1;
        events.save(new SessionEvent(UUID.randomUUID(), session.getId(), seq, eventType, payloadJson, Instant.now()));
    }
}

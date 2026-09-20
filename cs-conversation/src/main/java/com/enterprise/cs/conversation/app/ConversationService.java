package com.enterprise.cs.conversation.app;

import com.enterprise.cs.commons.constant.ErrorCodes;
import com.enterprise.cs.commons.exception.BusinessException;
import com.enterprise.cs.conversation.api.ConversationPort;
import com.enterprise.cs.conversation.domain.Message;
import com.enterprise.cs.conversation.domain.MessageRepository;
import com.enterprise.cs.conversation.domain.Session;
import com.enterprise.cs.conversation.domain.SessionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话应用服务（《02》分层：app 为用例与事务边界）。
 * 阶段：M0批3——开态/入站消息最小用例；状态机、事件溯源、seq 编排于 M1批1 引入。
 */
@Service
@Transactional
public class ConversationService implements ConversationPort {

    private final SessionRepository sessions;
    private final MessageRepository messages;

    public ConversationService(SessionRepository sessions, MessageRepository messages) {
        this.sessions = sessions;
        this.messages = messages;
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
}

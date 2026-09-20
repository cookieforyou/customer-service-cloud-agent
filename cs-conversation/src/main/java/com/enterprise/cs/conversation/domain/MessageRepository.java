package com.enterprise.cs.conversation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** 消息仓储（《12》§2.1）；幂等查询对应唯一键 (tenant_id, channel, channel_msg_id)（《03》§5）。阶段：M0批2 数据基线。 */
public interface MessageRepository extends JpaRepository<Message, UUID> {

    boolean existsByTenantIdAndChannelAndChannelMsgId(String tenantId, String channel, String channelMsgId);

    Optional<Message> findByTenantIdAndChannelAndChannelMsgId(String tenantId, String channel, String channelMsgId);

    Optional<Message> findFirstBySessionIdOrderBySeqDesc(UUID sessionId);
}

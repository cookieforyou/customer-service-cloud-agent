package com.enterprise.cs.conversation.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 消息仓储（《12》§2.1）；幂等查询对应唯一键 (tenant_id, channel, channel_msg_id)（《03》§5）。阶段：M0批2 数据基线。 */
public interface MessageRepository extends JpaRepository<Message, UUID> {

    boolean existsByTenantIdAndChannelAndChannelMsgId(String tenantId, String channel, String channelMsgId);

    Optional<Message> findByTenantIdAndChannelAndChannelMsgId(String tenantId, String channel, String channelMsgId);

    Optional<Message> findFirstBySessionIdOrderBySeqDesc(UUID sessionId);

    /** 窗口读拼（SessionMemoryAdvisor，《03》§3.2）：限 role/content_type，排除本轮入站消息，seq 降序取 limit 后反转。 */
    List<Message> findBySessionIdAndRoleInAndContentTypeAndIdNotOrderBySeqDesc(
            UUID sessionId, Collection<String> roles, String contentType, UUID excludeMessageId, Pageable pageable);
}

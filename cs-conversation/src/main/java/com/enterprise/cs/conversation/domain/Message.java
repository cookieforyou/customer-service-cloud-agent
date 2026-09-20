package com.enterprise.cs.conversation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话消息（《03》§1、《08》§1 统一信封落库形态）。
 * 表 cs_message；tenant_id/channel 冗余自会话——幂等唯一键 (tenant_id, channel, channel_msg_id) 直查（《03》§5）；
 * channel_msg_id 仅入站用户消息持有（AI/SYSTEM 消息为 NULL，PG 唯一键允许多 NULL）。阶段：M0批2 数据基线。
 */
@Entity
@Table(name = "cs_message",
        uniqueConstraints = @UniqueConstraint(name = "uk_cs_message_idem", columnNames = {"tenant_id", "channel", "channel_msg_id"}),
        indexes = @Index(name = "idx_cs_message_session_seq", columnList = "session_id, seq"))
public class Message {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "turn_id")
    private UUID turnId;

    @Column(nullable = false)
    private Integer seq;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(name = "content_type", nullable = false, length = 16)
    private String contentType;

    @Column(columnDefinition = "text")
    private String content;

    @Column(name = "content_redacted", columnDefinition = "text")
    private String contentRedacted;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 32)
    private String channel;

    @Column(name = "channel_msg_id", length = 128)
    private String channelMsgId;

    /** 扩展元数据（附件引用/帧序号等），JSON 文本。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String meta;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Message() {
    }

    public Message(UUID id, UUID sessionId, UUID turnId, int seq,
                   String role, String contentType, String content,
                   String tenantId, String channel, String channelMsgId, Instant createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.seq = seq;
        this.role = role;
        this.contentType = contentType;
        this.content = content;
        this.tenantId = tenantId;
        this.channel = channel;
        this.channelMsgId = channelMsgId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getTurnId() {
        return turnId;
    }

    public void setTurnId(UUID turnId) {
        this.turnId = turnId;
    }

    public Integer getSeq() {
        return seq;
    }

    public String getRole() {
        return role;
    }

    public String getContentType() {
        return contentType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContentRedacted() {
        return contentRedacted;
    }

    public void setContentRedacted(String contentRedacted) {
        this.contentRedacted = contentRedacted;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getChannel() {
        return channel;
    }

    public String getChannelMsgId() {
        return channelMsgId;
    }

    public String getMeta() {
        return meta;
    }

    public void setMeta(String meta) {
        this.meta = meta;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

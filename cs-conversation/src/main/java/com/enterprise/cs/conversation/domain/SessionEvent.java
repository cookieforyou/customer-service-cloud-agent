package com.enterprise.cs.conversation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话事件（事件溯源主表，《03》§1）：状态迁移/路由/消息追加/工具/送审等非消息事实，
 * 上下文重建与审计回放共用。V3 迁移落表（M0批4）；trace_id 由观测批次（M0批5）回填。
 */
@Entity
@Table(name = "cs_session_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_cs_session_event_seq", columnNames = {"session_id", "seq"}))
public class SessionEvent {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(nullable = false)
    private Integer seq;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    /** 事件载荷（{@code event_type} 枚举见《12》§2），JSON 文本。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected SessionEvent() {
    }

    public SessionEvent(UUID id, UUID sessionId, int seq, String eventType, String payload,
                        String traceId, Instant occurredAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.seq = seq;
        this.eventType = eventType;
        this.payload = payload;
        this.traceId = traceId;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public Integer getSeq() {
        return seq;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public String getTraceId() {
        return traceId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}

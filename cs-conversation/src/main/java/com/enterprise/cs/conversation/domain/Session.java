package com.enterprise.cs.conversation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话聚合根（《03》§1、《12》§2）。表 cs_session，RLS 租户隔离（V1 迁移，fail-closed）。
 * id 应用侧生成（UUID）；state 迁移 M1批1 状态机枚举化。阶段：M0批2 数据基线。
 */
@Entity
@Table(name = "cs_session", indexes = @Index(name = "idx_cs_session_tenant_active", columnList = "tenant_id, last_active_at"))
public class Session {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 32)
    private String channel;

    @Column(name = "visitor_id", length = 64)
    private String visitorId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(nullable = false, length = 32)
    private String state;

    @Column(name = "intent_current", length = 64)
    private String intentCurrent;

    @Column(name = "skill_group_id")
    private Long skillGroupId;

    @Column(name = "agent_seat_id")
    private Long agentSeatId;

    @Column(name = "resolution_type", length = 16)
    private String resolutionType;

    @Column(name = "csat_score")
    private Short csatScore;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_active_at", nullable = false)
    private Instant lastActiveAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "close_reason", length = 64)
    private String closeReason;

    protected Session() {
    }

    public Session(UUID id, String tenantId, String channel, String visitorId, String state, Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.channel = channel;
        this.visitorId = visitorId;
        this.state = state;
        this.createdAt = now;
        this.lastActiveAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getChannel() {
        return channel;
    }

    public String getVisitorId() {
        return visitorId;
    }

    public void setVisitorId(String visitorId) {
        this.visitorId = visitorId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getIntentCurrent() {
        return intentCurrent;
    }

    public void setIntentCurrent(String intentCurrent) {
        this.intentCurrent = intentCurrent;
    }

    public Long getSkillGroupId() {
        return skillGroupId;
    }

    public void setSkillGroupId(Long skillGroupId) {
        this.skillGroupId = skillGroupId;
    }

    public Long getAgentSeatId() {
        return agentSeatId;
    }

    public void setAgentSeatId(Long agentSeatId) {
        this.agentSeatId = agentSeatId;
    }

    public String getResolutionType() {
        return resolutionType;
    }

    public void setResolutionType(String resolutionType) {
        this.resolutionType = resolutionType;
    }

    public Short getCsatScore() {
        return csatScore;
    }

    public void setCsatScore(Short csatScore) {
        this.csatScore = csatScore;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Instant lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public String getCloseReason() {
        return closeReason;
    }

    public void setCloseReason(String closeReason) {
        this.closeReason = closeReason;
    }
}

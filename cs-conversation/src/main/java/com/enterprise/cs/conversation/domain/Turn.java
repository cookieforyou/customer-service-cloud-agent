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
 * 轮次：一次「用户消息 → 平台完整应答」的编排执行与计量单元（《03》§1、《04》§2 TurnPlan 落点）。
 * 表 cs_turn；route_decision 为路由证据 JSON（可解释、可回归，《04》§3）。阶段：M0批2 数据基线。
 */
@Entity
@Table(name = "cs_turn", uniqueConstraints = @UniqueConstraint(name = "uk_cs_turn_session_seq", columnNames = {"session_id", "seq"}))
public class Turn {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(nullable = false)
    private Integer seq;

    @Column(length = 64)
    private String intent;

    /** 路由决策证据（L1/L2 命中、置信度等），JSON 文本。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String routeDecision;

    @Column(name = "model_tier", length = 8)
    private String modelTier;

    @Column(name = "tokens_in")
    private Integer tokensIn;

    @Column(name = "tokens_out")
    private Integer tokensOut;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(nullable = false, length = 16)
    private String state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Turn() {
    }

    public Turn(UUID id, UUID sessionId, int seq, String state, Instant createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.seq = seq;
        this.state = state;
        this.createdAt = createdAt;
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

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public String getRouteDecision() {
        return routeDecision;
    }

    public void setRouteDecision(String routeDecision) {
        this.routeDecision = routeDecision;
    }

    public String getModelTier() {
        return modelTier;
    }

    public void setModelTier(String modelTier) {
        this.modelTier = modelTier;
    }

    public Integer getTokensIn() {
        return tokensIn;
    }

    public void setTokensIn(Integer tokensIn) {
        this.tokensIn = tokensIn;
    }

    public Integer getTokensOut() {
        return tokensOut;
    }

    public void setTokensOut(Integer tokensOut) {
        this.tokensOut = tokensOut;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

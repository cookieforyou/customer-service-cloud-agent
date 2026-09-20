-- M0批2 数据基线：《12》§2 会话域三表 + RLS 租户隔离（fail-closed）
-- 说明：cs_message 冗余 tenant_id/channel 列——《03》§5 幂等唯一键 (tenant_id, channel, channel_msg_id) 落本表直查

CREATE TABLE cs_session (
    id              UUID PRIMARY KEY,
    tenant_id       TEXT        NOT NULL,
    channel         TEXT        NOT NULL,
    visitor_id      TEXT,
    user_id         TEXT,
    state           TEXT        NOT NULL,
    intent_current  TEXT,
    skill_group_id  BIGINT,
    agent_seat_id   BIGINT,
    resolution_type TEXT,
    csat_score      SMALLINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_active_at  TIMESTAMPTZ NOT NULL,
    closed_at       TIMESTAMPTZ,
    close_reason    TEXT
);

CREATE INDEX idx_cs_session_tenant_active ON cs_session (tenant_id, last_active_at);

CREATE TABLE cs_turn (
    id             UUID PRIMARY KEY,
    session_id     UUID        NOT NULL REFERENCES cs_session (id),
    seq            INTEGER     NOT NULL,
    intent         TEXT,
    route_decision JSONB,
    model_tier     TEXT,
    tokens_in      INTEGER,
    tokens_out     INTEGER,
    latency_ms     INTEGER,
    state          TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_cs_turn_session_seq UNIQUE (session_id, seq)
);

CREATE TABLE cs_message (
    id               UUID PRIMARY KEY,
    session_id       UUID        NOT NULL REFERENCES cs_session (id),
    turn_id          UUID        REFERENCES cs_turn (id),
    seq              INTEGER     NOT NULL,
    role             TEXT        NOT NULL,
    content_type     TEXT        NOT NULL,
    content          TEXT,
    content_redacted TEXT,
    tenant_id        TEXT        NOT NULL,
    channel          TEXT        NOT NULL,
    channel_msg_id   TEXT,
    meta             JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_cs_message_idem UNIQUE (tenant_id, channel, channel_msg_id)
);

CREATE INDEX idx_cs_message_session_seq ON cs_message (session_id, seq);

-- RLS 租户隔离（《12》§2.2）：策略 fail-closed——GUC cs.tenant_id 未设置时 current_setting(...,true)=NULL，
-- 行过滤为空且 INSERT 被 WITH CHECK 拒绝（无租户上下文即无数据访问）
ALTER TABLE cs_session   ENABLE ROW LEVEL SECURITY;
ALTER TABLE cs_session   FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cs_session FOR ALL
    USING (tenant_id = current_setting('cs.tenant_id', true));

ALTER TABLE cs_turn      ENABLE ROW LEVEL SECURITY;
ALTER TABLE cs_turn      FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cs_turn FOR ALL
    USING (session_id IN (SELECT id FROM cs_session));

ALTER TABLE cs_message   ENABLE ROW LEVEL SECURITY;
ALTER TABLE cs_message   FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cs_message FOR ALL
    USING (tenant_id = current_setting('cs.tenant_id', true));

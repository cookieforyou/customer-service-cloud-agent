-- M0批4 最小对话链：cs_session_event 事件溯源主表（《03》§1、《12》§2 会话域）
-- 上下文重建与审计回放的事实来源；M0批4 落 ROUTE_DECIDED / MESSAGE_APPENDED 两类事件
-- 分区（按月，pg_partman）挂 M1 数据规模起量后实施（《12》§2 分区策略）

CREATE TABLE cs_session_event (
    id          UUID PRIMARY KEY,
    session_id  UUID        NOT NULL REFERENCES cs_session (id),
    seq         INTEGER     NOT NULL,
    event_type  TEXT        NOT NULL,
    payload     JSONB,
    trace_id    TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_cs_session_event_seq UNIQUE (session_id, seq)
);

-- RLS 与 cs_turn 同款：经会话归属判定（fail-closed，无租户上下文即不可见，《12》§2.2）
ALTER TABLE cs_session_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE cs_session_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cs_session_event FOR ALL
    USING (session_id IN (SELECT id FROM cs_session));

-- virbius-engine 表结构（PostgreSQL / MySQL / SQLite 通用）

CREATE TABLE IF NOT EXISTS tb_cache_meta (
    tenant_id        VARCHAR(64) PRIMARY KEY,
    policy_version   VARCHAR(64) NOT NULL,
    cache_generation INTEGER NOT NULL DEFAULT 0,
    loaded_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tb_rule_cache_entry (
    tenant_id       VARCHAR(64) NOT NULL,
    rule_id         VARCHAR(128) NOT NULL,
    rule_revision   INTEGER NOT NULL,
    layer           VARCHAR(32) NOT NULL,
    runtime         VARCHAR(32) NOT NULL,
    reason_code     VARCHAR(64) NOT NULL,
    risk_score      INTEGER NOT NULL DEFAULT 100,
    enforce_mode    VARCHAR(32) NOT NULL DEFAULT 'dry_run',
    rollout_state   VARCHAR(16) NOT NULL DEFAULT 'dry_run',
    intent_action   VARCHAR(32) NOT NULL DEFAULT 'deny',
    canary_percent  INTEGER NOT NULL DEFAULT 5,
    body            TEXT,
    PRIMARY KEY (tenant_id, rule_id),
    CHECK (risk_score >= 0 AND risk_score <= 100)
);

CREATE TABLE IF NOT EXISTS tb_audit_events (
    trace_id          VARCHAR(128) NOT NULL,
    tenant_id         VARCHAR(64) NOT NULL,
    rule_id           VARCHAR(128) NOT NULL,
    rule_revision     INTEGER NOT NULL,
    intercepted_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    trace_id_source   VARCHAR(32),
    scene             VARCHAR(64) NOT NULL,
    layer             VARCHAR(32) NOT NULL,
    reason_code       VARCHAR(64) NOT NULL,
    effective_action  VARCHAR(32) NOT NULL,
    max_risk_score    INTEGER NOT NULL DEFAULT 0,
    user_id           VARCHAR(128),
    device_id         VARCHAR(128),
    PRIMARY KEY (trace_id, tenant_id, rule_id, rule_revision, intercepted_at)
);

CREATE INDEX IF NOT EXISTS idx_tb_audit_trace ON tb_audit_events (trace_id);
CREATE INDEX IF NOT EXISTS idx_tb_audit_tenant_time ON tb_audit_events (tenant_id, intercepted_at);

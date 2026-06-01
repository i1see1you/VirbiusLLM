-- virbius-control 表结构（PostgreSQL / MySQL / SQLite 通用 JDBC 方言）
-- 时间列用 TIMESTAMP；主键/短文本用 VARCHAR；JSON 用 TEXT

CREATE TABLE IF NOT EXISTS tb_tenants (
    tenant_id   VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tb_bundles (
    tenant_id       VARCHAR(64) NOT NULL,
    bundle_id       VARCHAR(128) NOT NULL,
    version         VARCHAR(64) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'draft',
    publish_id      VARCHAR(64),
    sync_ack_json   TEXT,
    metadata_json   TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, bundle_id, version)
);

CREATE TABLE IF NOT EXISTS tb_rules_current (
    tenant_id         VARCHAR(64) NOT NULL,
    rule_id           VARCHAR(128) NOT NULL,
    current_revision  INTEGER NOT NULL,
    bundle_id         VARCHAR(128) NOT NULL,
    layer             VARCHAR(32) NOT NULL,
    runtime           VARCHAR(32) NOT NULL,
    reason_code       VARCHAR(64) NOT NULL,
    enforce_mode    VARCHAR(32) NOT NULL DEFAULT 'dry_run',
    intent_action   VARCHAR(32) NOT NULL DEFAULT 'deny',
    rule_status       VARCHAR(16) NOT NULL DEFAULT 'draft',
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, rule_id)
);

CREATE TABLE IF NOT EXISTS tb_rule_history (
    tenant_id       VARCHAR(64) NOT NULL,
    rule_id         VARCHAR(128) NOT NULL,
    rule_revision   INTEGER NOT NULL,
    bundle_id       VARCHAR(128) NOT NULL,
    layer           VARCHAR(32) NOT NULL,
    runtime         VARCHAR(32) NOT NULL,
    reason_code     VARCHAR(64) NOT NULL,
    risk_score      INTEGER NOT NULL DEFAULT 100,
    scope_json      TEXT,
    body_json       TEXT,
    body_hash       VARCHAR(64),
    enforce_mode    VARCHAR(32) NOT NULL DEFAULT 'dry_run',
    intent_action   VARCHAR(32) NOT NULL DEFAULT 'deny',
    canary_percent  INTEGER DEFAULT 5,
    rule_status     VARCHAR(16) NOT NULL DEFAULT 'draft',
    effective_from  TIMESTAMP NOT NULL,
    effective_to    TIMESTAMP,
    modified_at     TIMESTAMP NOT NULL,
    modified_by     VARCHAR(64),
    publish_id      VARCHAR(64),
    PRIMARY KEY (tenant_id, rule_id, rule_revision),
    CHECK (risk_score >= 0 AND risk_score <= 100),
    CHECK (rule_status IN ('draft', 'active', 'disabled'))
);

CREATE INDEX IF NOT EXISTS idx_tb_rule_history_tenant_layer
    ON tb_rule_history (tenant_id, layer);

CREATE INDEX IF NOT EXISTS idx_tb_rule_history_effective
    ON tb_rule_history (tenant_id, rule_id, effective_to);

-- risk_score：0=放行，1–99=灰区，100=应拦截
-- 旧库请 VIRBIUS_REBUILD_DB=1 重建

CREATE TABLE IF NOT EXISTS tb_access_list (
    tenant_id    VARCHAR(64) NOT NULL,
    polarity     VARCHAR(16) NOT NULL,
    dimension    VARCHAR(32) NOT NULL,
    value        VARCHAR(512) NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, polarity, dimension, value),
    CHECK (polarity IN ('deny', 'allow')),
    CHECK (dimension IN ('keyword', 'user_id', 'device_id', 'ip_cidr', 'var'))
);

CREATE INDEX IF NOT EXISTS idx_tb_access_list_tenant
    ON tb_access_list (tenant_id, polarity, dimension);

-- Named access lists (list_name model; allow/deny via list_match rule risk_score)
CREATE TABLE IF NOT EXISTS tb_access_list_meta (
    tenant_id    VARCHAR(64) NOT NULL,
    list_name    VARCHAR(128) NOT NULL,
    dimension    VARCHAR(32) NOT NULL,
    remark       VARCHAR(512),
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, list_name)
);

CREATE TABLE IF NOT EXISTS tb_access_list_entry (
    tenant_id    VARCHAR(64) NOT NULL,
    list_name    VARCHAR(128) NOT NULL,
    value        VARCHAR(512) NOT NULL,
    remark       VARCHAR(512),
    expires_at   TIMESTAMP,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, list_name, value)
);

CREATE INDEX IF NOT EXISTS idx_tb_access_list_entry_tenant
    ON tb_access_list_entry (tenant_id, list_name);

-- Cumulative counter definitions
CREATE TABLE IF NOT EXISTS tb_cumulative (
    tenant_id              VARCHAR(64) NOT NULL,
    cumulative_name        VARCHAR(128) NOT NULL,
    description            VARCHAR(512),
    dimension              VARCHAR(32) NOT NULL,
    window_kind            VARCHAR(32) NOT NULL,
    window_minutes         INTEGER,
    window_hours           INTEGER,
    timezone               VARCHAR(64),
    threshold              INTEGER NOT NULL,
    compare_op             VARCHAR(16) NOT NULL DEFAULT 'gte',
    on_exceed_suggest      VARCHAR(16) NOT NULL DEFAULT 'block',
    on_exceed_risk_score   INTEGER NOT NULL DEFAULT 100,
    on_exceed_reason_code  VARCHAR(64) NOT NULL,
    priority               INTEGER NOT NULL DEFAULT 0,
    status                 VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, cumulative_name),
    CHECK (window_kind IN ('rolling', 'calendar_day')),
    CHECK (status IN ('active', 'disabled')),
    CHECK (threshold >= 1)
);

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
    enforce_mode      VARCHAR(32) NOT NULL DEFAULT 'dry_run',
    rule_status       VARCHAR(16) NOT NULL DEFAULT 'active',
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
    canary_percent  INTEGER DEFAULT 5,
    rule_status     VARCHAR(16) NOT NULL DEFAULT 'active',
    effective_from  TIMESTAMP NOT NULL,
    effective_to    TIMESTAMP,
    modified_at     TIMESTAMP NOT NULL,
    modified_by     VARCHAR(64),
    publish_id      VARCHAR(64),
    PRIMARY KEY (tenant_id, rule_id, rule_revision),
    CHECK (risk_score >= 0 AND risk_score <= 100),
    CHECK (rule_status IN ('active', 'disabled'))
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

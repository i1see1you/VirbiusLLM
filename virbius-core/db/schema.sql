-- virbius-core 端侧本地库（PostgreSQL / MySQL / SQLite 通用，可选）

CREATE TABLE IF NOT EXISTS tb_edge_manifest (
    tenant_id       VARCHAR(64) NOT NULL DEFAULT 'default',
    bundle_id       VARCHAR(128) NOT NULL,
    bundle_version  VARCHAR(64) NOT NULL,
    manifest_url    VARCHAR(1024),
    fetched_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, bundle_id, bundle_version)
);

CREATE TABLE IF NOT EXISTS tb_edge_rule_bin (
    tenant_id       VARCHAR(64) NOT NULL,
    rule_id         VARCHAR(128) NOT NULL,
    rule_revision   INTEGER NOT NULL,
    artifact_type   VARCHAR(32) NOT NULL,
    local_path      VARCHAR(1024) NOT NULL,
    loaded_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, rule_id, rule_revision, artifact_type)
);

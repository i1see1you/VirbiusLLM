-- virbius-compiler 表结构（PostgreSQL / MySQL / SQLite 通用）

CREATE TABLE IF NOT EXISTS tb_compile_runs (
    run_id          VARCHAR(64) PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    bundle_id       VARCHAR(128) NOT NULL,
    bundle_version  VARCHAR(64) NOT NULL,
    gateway_target  VARCHAR(32) NOT NULL DEFAULT 'apisix',
    status          VARCHAR(32) NOT NULL DEFAULT 'pending',
    input_path      VARCHAR(512),
    output_dir      VARCHAR(512),
    error_message   TEXT,
    started_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at     TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tb_compile_runs_bundle
    ON tb_compile_runs (tenant_id, bundle_id, bundle_version);

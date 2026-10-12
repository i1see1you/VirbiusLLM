-- QA proxy reply tables (M1a). Portable JDBC DDL (PostgreSQL / MySQL / SQLite).

CREATE TABLE IF NOT EXISTS tb_qa_meta (
    tenant_id      VARCHAR(64)  NOT NULL,
    qa_id          VARCHAR(64)  NOT NULL,
    name           VARCHAR(255) NOT NULL,
    description    TEXT,
    status         VARCHAR(16)  NOT NULL DEFAULT 'draft',
    rollout_state  VARCHAR(16)  NOT NULL DEFAULT 'dry_run',
    canary_percent INTEGER      NOT NULL DEFAULT 0,
    revision       BIGINT       NOT NULL DEFAULT 0,
    created_by     VARCHAR(64),
    updated_by     VARCHAR(64),
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, qa_id)
);

CREATE TABLE IF NOT EXISTS tb_qa_entry (
    tenant_id        VARCHAR(64) NOT NULL,
    qa_id            VARCHAR(64) NOT NULL,
    entry_id         VARCHAR(64) NOT NULL,
    question         TEXT,
    variants         TEXT        NOT NULL DEFAULT '[]',
    answer           TEXT        NOT NULL,
    match_mode       VARCHAR(16) NOT NULL DEFAULT 'exact',
    tags             TEXT        NOT NULL DEFAULT '[]',
    priority         INTEGER     NOT NULL DEFAULT 0,
    enabled          INTEGER     NOT NULL DEFAULT 1,
    expires_at       TIMESTAMP,
    last_precheck_at TIMESTAMP,
    last_precheck_ok INTEGER,
    created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, qa_id, entry_id)
);

CREATE TABLE IF NOT EXISTS tb_qa_history (
    history_id  VARCHAR(64) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL,
    qa_id       VARCHAR(64) NOT NULL,
    entry_id    VARCHAR(64),
    action      VARCHAR(32) NOT NULL,
    actor       VARCHAR(64),
    snapshot    TEXT,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (history_id)
);

CREATE TABLE IF NOT EXISTS tb_qa_publish_job (
    publish_id        VARCHAR(64) NOT NULL,
    tenant_id         VARCHAR(64) NOT NULL,
    qa_id             VARCHAR(64) NOT NULL,
    status            VARCHAR(16) NOT NULL,
    snapshot_revision BIGINT,
    errors            TEXT,
    created_by        VARCHAR(64),
    created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (publish_id)
);

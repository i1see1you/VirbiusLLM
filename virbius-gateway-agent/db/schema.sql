-- virbius-gateway-agent 本地库（PostgreSQL / MySQL / SQLite 通用，可选）

CREATE TABLE IF NOT EXISTS tb_agent_evaluate_log (
    trace_id         VARCHAR(128) NOT NULL,
    tenant_id        VARCHAR(64) NOT NULL,
    scene            VARCHAR(64) NOT NULL,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    effective_action VARCHAR(32),
    max_risk_score   INTEGER NOT NULL DEFAULT 0,
    degraded         INTEGER NOT NULL DEFAULT 0,
    latency_ms       INTEGER,
    PRIMARY KEY (trace_id, tenant_id, scene, created_at)
);

CREATE INDEX IF NOT EXISTS idx_tb_agent_log_trace ON tb_agent_evaluate_log (trace_id);

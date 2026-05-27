-- engine 种子（PostgreSQL / MySQL / SQLite 通用）

INSERT INTO tb_cache_meta (tenant_id, policy_version, cache_generation, loaded_at)
SELECT 'default', '0.1.0', 0, CURRENT_TIMESTAMP FROM (SELECT 1) AS _one
WHERE NOT EXISTS (SELECT 1 FROM tb_cache_meta WHERE tenant_id = 'default');

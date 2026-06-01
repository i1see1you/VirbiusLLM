package io.virbius.control.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRolloutMetricsRepository implements RolloutMetricsRepository {

    private final JdbcTemplate jdbc;

    public JdbcRolloutMetricsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long countReview24h(String tenantId, String ruleId) {
        Long n = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(cnt_review), 0) FROM tb_rule_metrics_1h
                WHERE tenant_id = ? AND rule_id = ?
                  AND hour_bucket >= datetime('now', '-24 hours')
                """,
                Long.class,
                tenantId,
                ruleId);
        return n != null ? n : 0L;
    }

    @Override
    public long countTotalRequests24h(String tenantId) {
        Long n = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(cnt_total_requests), 0) FROM tb_rule_metrics_1h
                WHERE tenant_id = ? AND hour_bucket >= datetime('now', '-24 hours')
                """,
                Long.class,
                tenantId);
        return n != null ? n : 0L;
    }

    @Override
    public long countBlockInCanary24h(String tenantId, String ruleId) {
        Long n = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(cnt_block), 0) FROM tb_rule_metrics_1h
                WHERE tenant_id = ? AND rule_id = ? AND rollout_state = 'canary'
                  AND hour_bucket >= datetime('now', '-24 hours')
                """,
                Long.class,
                tenantId,
                ruleId);
        return n != null ? n : 0L;
    }
}

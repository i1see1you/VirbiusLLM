package io.virbius.control.job;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MetricsRollupJob {

    private final JdbcTemplate jdbc;

    public MetricsRollupJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${rollout.metrics.rollup-ms:900000}")
    public void rollup() {
        jdbc.update(
                """
                INSERT OR REPLACE INTO tb_rule_metrics_1h (
                  tenant_id, rule_id, hour_bucket, rollout_state, canary_percent,
                  cnt_review, cnt_block, cnt_captcha, cnt_allow, cnt_total_requests, cnt_degraded
                )
                SELECT tenant_id, rule_id,
                       strftime('%%Y-%%m-%%d %%H:00:00', intercepted_at) AS hour_bucket,
                       rollout_state, canary_percent,
                       SUM(CASE WHEN effective_action = 'review' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN effective_action = 'block' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN effective_action = 'captcha' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN effective_action = 'allow' THEN 1 ELSE 0 END),
                       COUNT(*),
                       SUM(CASE WHEN degraded = 1 THEN 1 ELSE 0 END)
                FROM tb_audit_events
                WHERE intercepted_at >= datetime('now', '-2 hours')
                GROUP BY tenant_id, rule_id, hour_bucket, rollout_state, canary_percent
                """);
    }
}

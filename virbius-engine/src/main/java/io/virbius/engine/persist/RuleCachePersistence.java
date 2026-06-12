package io.virbius.engine.persist;

import io.virbius.engine.cache.RuleEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class RuleCachePersistence {

    private final JdbcTemplate jdbc;

    public RuleCachePersistence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<RuleEntry> loadAll() {
        return jdbc.query(
                "SELECT tenant_id, rule_id, rule_revision, layer, runtime, reason_code, risk_score, " +
                "enforce_mode, rollout_state, intent_action, canary_percent, is_async, async_action_config, body " +
                "FROM tb_rule_cache_entry",
                this::mapRuleEntry);
    }

    private RuleEntry mapRuleEntry(ResultSet rs, int rowNum) throws SQLException {
        return new RuleEntry(
                /* tenantId */         rs.getString("tenant_id"),
                /* ruleId */           rs.getString("rule_id"),
                /* ruleRevision */     rs.getInt("rule_revision"),
                /* layer */            rs.getString("layer"),
                /* runtime */          rs.getString("runtime"),
                /* reasonCode */       rs.getString("reason_code"),
                /* riskScore */        rs.getInt("risk_score"),
                /* intentAction */     rs.getString("intent_action"),
                /* enforceMode */      rs.getString("enforce_mode"),
                /* canaryPercent */    rs.getInt("canary_percent"),
                /* rolloutState */     rs.getString("rollout_state"),
                /* body */             rs.getString("body"),
                /* scope */            null,
                /* isAsync */          rs.getInt("is_async") != 0,
                /* asyncActionConfig */rs.getString("async_action_config"));
    }

    public void save(String policyVersion, List<RuleEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        Map<String, List<RuleEntry>> byTenant =
                entries.stream().collect(Collectors.groupingBy(RuleEntry::tenantId));
        String loadedAt = Instant.now().toString();
        for (var e : byTenant.entrySet()) {
            String tenantId = e.getKey();
            List<RuleEntry> rules = e.getValue();
            jdbc.update(
                    """
                    INSERT INTO tb_cache_meta (tenant_id, policy_version, cache_generation, loaded_at)
                    VALUES (?, ?, 1, ?)
                    ON CONFLICT(tenant_id) DO UPDATE SET
                      policy_version = excluded.policy_version,
                      cache_generation = tb_cache_meta.cache_generation + 1,
                      loaded_at = excluded.loaded_at
                    """,
                    tenantId,
                    policyVersion,
                    loadedAt);
            jdbc.update("DELETE FROM tb_rule_cache_entry WHERE tenant_id = ?", tenantId);
            for (RuleEntry rule : rules) {
                    jdbc.update(
                            """
                            INSERT INTO tb_rule_cache_entry (
                              tenant_id, rule_id, rule_revision, layer, runtime,
                              reason_code, risk_score, intent_action, enforce_mode, rollout_state,
                              canary_percent, is_async, async_action_config, body
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                            rule.tenantId(),
                            rule.ruleId(),
                            rule.ruleRevision(),
                            rule.layer(),
                            rule.runtime(),
                            rule.reasonCode(),
                            rule.riskScore(),
                            rule.intentAction() != null ? rule.intentAction() : "deny",
                            rule.enforceMode(),
                            rule.rolloutStateOrDefault(),
                            rule.canaryPercent(),
                            rule.isAsync(),
                            rule.asyncActionConfig(),
                            rule.body());
            }
        }
    }
}

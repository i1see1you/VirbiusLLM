package io.virbius.engine.persist;

import io.virbius.engine.cache.RuleEntry;
import java.time.Instant;
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
                          reason_code, risk_score, enforce_mode, canary_percent, body
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        rule.tenantId(),
                        rule.ruleId(),
                        rule.ruleRevision(),
                        rule.layer(),
                        rule.runtime(),
                        rule.reasonCode(),
                        rule.riskScore(),
                        rule.enforceMode(),
                        rule.canaryPercent(),
                        rule.body());
            }
        }
    }
}

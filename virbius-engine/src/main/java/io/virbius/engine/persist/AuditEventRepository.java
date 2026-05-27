package io.virbius.engine.persist;

import io.virbius.engine.eval.EngineDecisionDto;
import io.virbius.engine.eval.EvaluateRequestDto;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditEventRepository {

    private final JdbcTemplate jdbc;

    public AuditEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            EvaluateRequestDto req,
            EngineDecisionDto decision,
            String ruleId,
            int ruleRevision,
            String reasonCode,
            String traceIdSource) {
        jdbc.update(
                """
                INSERT INTO tb_audit_events (
                  trace_id, trace_id_source, tenant_id, scene, layer,
                  rule_id, rule_revision, reason_code,
                  effective_action, would_block, intercepted_at, user_id, device_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                req.traceId(),
                traceIdSource,
                req.tenantId(),
                req.scene(),
                "cloud",
                ruleId != null ? ruleId : "",
                ruleRevision,
                reasonCode != null ? reasonCode : "",
                decision.effectiveAction(),
                decision.wouldBlock() ? 1 : 0,
                Instant.now().toString(),
                blankToNull(req.userId()),
                blankToNull(req.deviceId()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

package io.virbius.engine.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.engine.eval.EngineDecisionDto;
import io.virbius.engine.eval.EvaluateRequestDto;
import io.virbius.engine.persist.AuditEventRepository;
import io.virbius.policy.ActionMerge;
import io.virbius.policy.audit.AuditEventPublisher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path auditPath;
    private final AuditEventRepository auditRepository;
    private final boolean sqliteEnabled;
    private final AuditEventPublisher publisher;
    private final RuleCache ruleCache;

    public AuditWriter(
            @Value("${virbius.audit.engine-path:/tmp/virbius/engine-audit.jsonl}") String path,
            @Value("${virbius.audit.sqlite-enabled:true}") boolean sqliteEnabled,
            AuditEventRepository auditRepository,
            AuditEventPublisher publisher,
            RuleCache ruleCache) {
        this.auditPath = Path.of(path);
        this.sqliteEnabled = sqliteEnabled;
        this.auditRepository = auditRepository;
        this.publisher = publisher;
        this.ruleCache = ruleCache;
    }

    public void write(
            EvaluateRequestDto req,
            EngineDecisionDto decision,
            String ruleId,
            int ruleRevision,
            String reasonCode,
            boolean degraded) {
        if (sqliteEnabled) {
            try {
                auditRepository.insert(req, decision, ruleId, ruleRevision, reasonCode, "client");
            } catch (Exception e) {
                log.warn("audit sqlite write failed: {}", e.getMessage());
            }
        }
        try {
            RuleEntry rule = ruleId != null ? ruleCache.get(req.tenantId(), ruleId) : null;
            String rolloutState = rule != null ? rule.rolloutStateOrDefault() : "dry_run";
            Integer canaryPercent = null;
            Boolean inBucket = null;
            if (rule != null && "canary".equalsIgnoreCase(rolloutState) && rule.canaryPercent() > 0) {
                canaryPercent = rule.canaryPercent();
                inBucket = ActionMerge.inCanaryBucket(req.sessionId(), canaryPercent);
            }

            Files.createDirectories(auditPath.getParent());
            Map<String, Object> event = new HashMap<>();
            event.put("trace_id", req.traceId());
            event.put("trace_id_source", "client");
            event.put("tenant_id", req.tenantId());
            event.put("scene", req.scene());
            event.put("layer", "cloud");
            event.put("rule_id", ruleId != null ? ruleId : "");
            event.put("rule_revision", ruleRevision);
            event.put("reason_code", reasonCode != null ? reasonCode : "");
            event.put("effective_action", decision.effectiveAction());
            event.put("max_risk_score", decision.maxRiskScore());
            event.put("rollout_state", rolloutState);
            if (canaryPercent != null) {
                event.put("canary_percent", canaryPercent);
            }
            if (inBucket != null) {
                event.put("in_canary_bucket", inBucket);
            }
            event.put("degraded", degraded);
            event.put("intercepted_at", Instant.now().toString());
            if (req.userId() != null && !req.userId().isBlank()) {
                event.put("user_id", req.userId());
            }
            if (req.deviceId() != null && !req.deviceId().isBlank()) {
                event.put("device_id", req.deviceId());
            }
            String json = mapper.writeValueAsString(event);
            Map<String, String> streamFields = Map.of("payload", json);
            publisher.publish(streamFields);
            Files.writeString(auditPath, json + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("audit write failed: {}", e.getMessage());
        }
    }
}

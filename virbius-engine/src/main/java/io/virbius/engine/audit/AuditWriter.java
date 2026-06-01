package io.virbius.engine.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.virbius.engine.eval.EngineDecisionDto;
import io.virbius.engine.eval.EvaluateRequestDto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.virbius.engine.persist.AuditEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path auditPath;
    private final AuditEventRepository auditRepository;
    private final boolean sqliteEnabled;

    public AuditWriter(
            @Value("${virbius.audit.engine-path:/tmp/virbius/engine-audit.jsonl}") String path,
            @Value("${virbius.audit.sqlite-enabled:true}") boolean sqliteEnabled,
            AuditEventRepository auditRepository) {
        this.auditPath = Path.of(path);
        this.sqliteEnabled = sqliteEnabled;
        this.auditRepository = auditRepository;
    }

    public void write(EvaluateRequestDto req, EngineDecisionDto decision, String ruleId, int ruleRevision, String reasonCode) {
        if (sqliteEnabled) {
            try {
                auditRepository.insert(req, decision, ruleId, ruleRevision, reasonCode, "client");
            } catch (Exception e) {
                log.warn("audit sqlite write failed: {}", e.getMessage());
            }
        }
        try {
            Files.createDirectories(auditPath.getParent());
            ObjectNode node = mapper.createObjectNode();
            node.put("trace_id", req.traceId());
            node.put("trace_id_source", "client");
            node.put("tenant_id", req.tenantId());
            node.put("scene", req.scene());
            node.put("layer", "cloud");
            node.put("rule_id", ruleId != null ? ruleId : "");
            node.put("rule_revision", ruleRevision);
            node.put("reason_code", reasonCode != null ? reasonCode : "");
            node.put("effective_action", decision.effectiveAction());
            node.put("max_risk_score", decision.maxRiskScore());
            node.put("intercepted_at", Instant.now().toString());
            if (req.userId() != null && !req.userId().isBlank()) {
                node.put("user_id", req.userId());
            }
            if (req.deviceId() != null && !req.deviceId().isBlank()) {
                node.put("device_id", req.deviceId());
            }
            String line = mapper.writeValueAsString(node) + "\n";
            Files.writeString(auditPath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("audit write failed: {}", e.getMessage());
        }
    }
}

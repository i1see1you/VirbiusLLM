package io.virbius.engine.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.policy.audit.AuditEventPublisher;
import io.virbius.policy.CounterStore;
import io.virbius.engine.eval.EngineDecisionDto;
import io.virbius.engine.eval.EvaluateRequestDto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.virbius.engine.persist.AuditEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

@Component
public class AuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path auditPath;
    private final AuditEventRepository auditRepository;
    private final boolean sqliteEnabled;
    private final AuditEventPublisher publisher;

    public AuditWriter(
            @Value("${virbius.audit.engine-path:/tmp/virbius/engine-audit.jsonl}") String path,
            @Value("${virbius.audit.sqlite-enabled:true}") boolean sqliteEnabled,
            AuditEventRepository auditRepository,
            AuditEventPublisher publisher) {
        this.auditPath = Path.of(path);
        this.sqliteEnabled = sqliteEnabled;
        this.auditRepository = auditRepository;
        this.publisher = publisher;
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
            event.put("degraded", false);
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

@Configuration
class EngineAuditConfig {

    @Bean
    public Optional<JedisPool> engineJedisPool(@Value("${virbius.redis.url:}") String redisUrl) {
        return CounterStore.createPool(redisUrl);
    }

    @Bean
    public AuditEventPublisher auditEventPublisher(
            Optional<JedisPool> engineJedisPool,
            @Value("${audit.publish.backend:redis-stream}") String backend,
            @Value("${audit.publish.redis.stream-key:virbius:audit:events}") String streamKey) {
        return new AuditEventPublisher(engineJedisPool, backend, streamKey);
    }
}

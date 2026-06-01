package io.virbius.control.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadParams;
import redis.clients.jedis.resps.StreamEntry;

@Service
public class AuditIngestService {

    private static final Logger log = LoggerFactory.getLogger(AuditIngestService.class);

    private final Optional<JedisPool> pool;
    private final JdbcTemplate jdbc;
    private final String streamKey;
    private final ObjectMapper mapper = new ObjectMapper();
    private StreamEntryID lastId = StreamEntryID.LAST_ENTRY;

    public AuditIngestService(
            Optional<JedisPool> jedisPool,
            JdbcTemplate jdbc,
            @Value("${audit.ingest.redis.stream-key:virbius:audit:events}") String streamKey) {
        this.pool = jedisPool;
        this.jdbc = jdbc;
        this.streamKey = streamKey;
    }

    @Scheduled(fixedDelayString = "${audit.ingest.poll-ms:5000}")
    public void poll() {
        if (pool.isEmpty()) {
            return;
        }
        try (Jedis jedis = pool.get().getResource()) {
            XReadParams params = XReadParams.xReadParams().count(32).block(500);
            List<Map.Entry<String, List<StreamEntry>>> batches =
                    jedis.xread(params, Map.of(streamKey, lastId));
            if (batches == null) {
                return;
            }
            for (Map.Entry<String, List<StreamEntry>> batch : batches) {
                for (StreamEntry entry : batch.getValue()) {
                    lastId = entry.getID();
                    ingestFields(entry.getFields());
                }
            }
        } catch (Exception e) {
            log.debug("audit ingest poll: {}", e.getMessage());
        }
    }

    private void ingestFields(Map<String, String> fields) {
        try {
            String payload = fields.get("payload");
            if (payload == null) {
                payload = mapper.writeValueAsString(fields);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> event = mapper.readValue(payload, Map.class);
            String traceId = str(event.get("trace_id"));
            String tenantId = str(event.get("tenant_id"));
            if (traceId.isBlank() || tenantId.isBlank()) {
                return;
            }
            String eventId = traceId + ":" + str(event.get("rule_id")) + ":" + str(event.get("intercepted_at"));
            jdbc.update(
                    """
                    INSERT OR IGNORE INTO tb_audit_events (
                      event_id, trace_id, trace_id_source, tenant_id, scene, layer,
                      rule_id, rule_revision, reason_code, effective_action, max_risk_score,
                      rollout_state, canary_percent, in_canary_bucket, degraded,
                      intercepted_at, user_id, device_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    eventId,
                    traceId,
                    str(event.get("trace_id_source")),
                    tenantId,
                    str(event.get("scene")),
                    str(event.get("layer")),
                    str(event.get("rule_id")),
                    intVal(event.get("rule_revision")),
                    str(event.get("reason_code")),
                    str(event.get("effective_action")),
                    intVal(event.get("max_risk_score")),
                    str(event.get("rollout_state")),
                    event.get("canary_percent") != null ? intVal(event.get("canary_percent")) : null,
                    boolInt(event.get("in_canary_bucket")),
                    boolInt(event.get("degraded")),
                    str(event.get("intercepted_at")),
                    str(event.get("user_id")),
                    str(event.get("device_id")));
        } catch (Exception e) {
            log.warn("audit ingest row failed: {}", e.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int intVal(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(str(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private static Integer boolInt(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return "true".equalsIgnoreCase(str(o)) ? 1 : 0;
    }
}

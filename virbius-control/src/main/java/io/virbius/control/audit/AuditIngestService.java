package io.virbius.control.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final AuditEventIngestor ingestor;
    private final String streamKey;
    private final ObjectMapper mapper = new ObjectMapper();
    private StreamEntryID lastId = StreamEntryID.LAST_ENTRY;

    public AuditIngestService(
            Optional<JedisPool> jedisPool,
            AuditEventIngestor ingestor,
            @Value("${audit.ingest.redis.stream-key:virbius:audit:events}") String streamKey) {
        this.pool = jedisPool;
        this.ingestor = ingestor;
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
            ingestor.ingestPayloadJson(payload);
        } catch (Exception e) {
            log.warn("audit ingest row failed: {}", e.getMessage());
        }
    }
}

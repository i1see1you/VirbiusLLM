package io.virbius.policy.audit;

import java.util.Map;
import java.util.Optional;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;

/** Publish audit events to Redis Stream (default) or log-only Kafka stub. */
public class AuditEventPublisher {

    private final Optional<JedisPool> redisPool;
    private final String streamKey;
    private final String backend;

    public AuditEventPublisher(Optional<JedisPool> redisPool, String backend, String streamKey) {
        this.redisPool = redisPool;
        this.backend = backend != null ? backend : "redis-stream";
        this.streamKey = streamKey != null && !streamKey.isBlank() ? streamKey : "virbius:audit:events";
    }

    public void publish(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        if ("kafka".equalsIgnoreCase(backend)) {
            // PoC: Kafka wiring via env; fallback log until broker configured
            org.slf4j.LoggerFactory.getLogger(AuditEventPublisher.class)
                    .info("audit kafka publish (stub) topic=virbius.audit.events fields={}", fields.keySet());
            return;
        }
        if (redisPool.isEmpty()) {
            return;
        }
        try (Jedis jedis = redisPool.get().getResource()) {
            StreamEntryID id = jedis.xadd(streamKey, StreamEntryID.NEW_ENTRY, fields);
            if (id == null) {
                org.slf4j.LoggerFactory.getLogger(AuditEventPublisher.class)
                        .warn("audit redis xadd returned null for stream {}", streamKey);
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(AuditEventPublisher.class)
                    .warn("audit redis publish failed: {}", e.getMessage());
        }
    }
}

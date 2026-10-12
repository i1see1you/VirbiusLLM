package io.virbius.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.config.ControlJedisPools;
import io.virbius.control.domain.QaEntry;
import io.virbius.control.domain.QaMeta;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class QaArtifactPublisher {

    private static final Logger log = LoggerFactory.getLogger(QaArtifactPublisher.class);
    private static final int SCHEMA_VERSION = 1;

    private final Optional<JedisPool> pool;
    private final ObjectMapper mapper;
    private final String pointerPrefix;
    private final String blobPrefix;
    private final String ackPrefix;

    public QaArtifactPublisher(
            ControlJedisPools jedisPools,
            ObjectMapper mapper,
            @Value("${virbius.qa.pointer-prefix:virbius:config:qa}") String pointerPrefix,
            @Value("${virbius.qa.blob-prefix:virbius:artifacts:qa}") String blobPrefix,
            @Value("${virbius.qa.ack-prefix:virbius:ack:qa}") String ackPrefix) {
        this.pool = jedisPools.pool();
        this.mapper = mapper;
        this.pointerPrefix = pointerPrefix;
        this.blobPrefix = blobPrefix;
        this.ackPrefix = ackPrefix;
    }

    public long publish(String tenantId, QaMeta meta, List<QaEntry> entries) {
        if (pool.isEmpty()) {
            throw new IllegalStateException("redis unavailable for QA snapshot publish");
        }
        try (Jedis jedis = pool.get().getResource()) {
            long revision = jedis.incr("virbius:qa:seq:" + tenantId);
            QaMeta withRevision = new QaMeta(
                    meta.tenantId(),
                    meta.qaId(),
                    meta.name(),
                    meta.description(),
                    meta.status(),
                    meta.rolloutState(),
                    meta.canaryPercent(),
                    revision,
                    meta.createdAt(),
                    meta.updatedAt());
            jedis.set(blobPrefix + ":" + tenantId + ":r" + revision + ":qa", snapshotJson(withRevision, entries));
            Map<String, String> pointer = new LinkedHashMap<>();
            pointer.put("qa_revision", String.valueOf(revision));
            pointer.put("qa_id", meta.qaId());
            pointer.put("updated_at", Instant.now().toString());
            jedis.hset(pointerPrefix + ":" + tenantId, pointer);
            return revision;
        }
    }

    public Map<String, Map<String, String>> readAcks(String tenantId) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (pool.isEmpty()) {
            return out;
        }
        String prefix = ackPrefix + ":" + tenantId + ":";
        try (Jedis jedis = pool.get().getResource()) {
            for (String key : jedis.keys(prefix + "*")) {
                Map<String, String> hash = jedis.hgetAll(key);
                if (!hash.isEmpty()) {
                    out.put(key.substring(prefix.length()), hash);
                }
            }
        } catch (Exception ex) {
            log.warn("read qa acks failed tenant={}: {}", tenantId, ex.getMessage());
        }
        return out;
    }

    public String snapshotJson(QaMeta meta, List<QaEntry> entries) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("revision", meta.revision());
        root.put("tenant_id", meta.tenantId());
        root.put("qa_id", meta.qaId());
        root.put("rollout_state", meta.rolloutState());
        root.put("canary_percent", meta.canaryPercent());
        List<Map<String, Object>> list = new ArrayList<>();
        for (QaEntry e : entries) {
            if (!e.enabled()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entry_id", e.entryId());
            row.put("question", e.question());
            row.put("variants", e.variants());
            row.put("answer", e.answer());
            row.put("match_mode", e.matchMode());
            row.put("tags", e.tags());
            row.put("priority", e.priority());
            row.put("enabled", true);
            row.put("expires_at", e.expiresAt() == null ? null : e.expiresAt().toString());
            list.add(row);
        }
        root.put("entries", list);
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("serialize qa snapshot failed: " + e.getMessage());
        }
    }
}

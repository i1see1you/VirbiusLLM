package io.virbius.control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.config.ControlJedisPools;
import io.virbius.control.domain.QaEntry;
import io.virbius.control.domain.QaMeta;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class QaArtifactPublisherSnapshotTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final QaArtifactPublisher publisher = new QaArtifactPublisher(
            new ControlJedisPools(""),
            mapper,
            "virbius:config:qa",
            "virbius:artifacts:qa",
            "virbius:ack:qa");

    private static QaMeta meta() {
        return new QaMeta(
                "default", "qa1", "核心价值", null, "enabled", "full", 0, 5L, Instant.now(), Instant.now());
    }

    private static QaEntry entry(String id, boolean enabled) {
        return new QaEntry(
                "default", "qa1", id, "问题", List.of("变体1"), "答案", "exact", List.of("policy"),
                10, enabled, null, null, null, Instant.now());
    }

    @Test
    void snapshotCarriesSchemaVersionAndEnabledEntriesOnly() throws Exception {
        String json = publisher.snapshotJson(meta(), List.of(entry("e1", true), entry("e2", false)));
        JsonNode root = mapper.readTree(json);
        assertEquals(1, root.get("schema_version").asInt());
        assertEquals(5, root.get("revision").asLong());
        assertEquals("full", root.get("rollout_state").asText());
        assertEquals(1, root.get("entries").size());
        JsonNode e1 = root.get("entries").get(0);
        assertEquals("e1", e1.get("entry_id").asText());
        assertEquals("exact", e1.get("match_mode").asText());
        assertEquals("变体1", e1.get("variants").get(0).asText());
        assertFalse(e1.has("expires_at") && !e1.get("expires_at").isNull());
    }
}

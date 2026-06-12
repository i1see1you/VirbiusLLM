package io.virbius.engine.persist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.cache.PolicyDataCache;
import io.virbius.engine.cache.PolicyDataCache.TenantPolicyData;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PolicyDataPersistence {

    private static final Logger log = LoggerFactory.getLogger(PolicyDataPersistence.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PolicyDataPersistence(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void save(String tenantId, TenantPolicyData data) {
        try {
            String json = mapper.writeValueAsString(data);
            jdbc.update(
                    "INSERT INTO tb_policy_data_cache (tenant_id, policy_data_json) VALUES (?, ?) " +
                    "ON CONFLICT(tenant_id) DO UPDATE SET policy_data_json = excluded.policy_data_json, " +
                    "updated_at = CURRENT_TIMESTAMP",
                    tenantId, json);
        } catch (JsonProcessingException e) {
            log.warn("failed to serialize policy data for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    public Optional<TenantPolicyData> load(String tenantId) {
        try {
            String json = jdbc.queryForObject(
                    "SELECT policy_data_json FROM tb_policy_data_cache WHERE tenant_id = ?",
                    String.class, tenantId);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(mapper.readValue(json, TenantPolicyData.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}

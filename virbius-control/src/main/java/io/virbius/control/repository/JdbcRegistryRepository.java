package io.virbius.control.repository;

import io.virbius.control.domain.BundleMetadataResources;
import io.virbius.control.domain.BundleVersion;
import io.virbius.control.domain.RiskScore;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.RuleStatusHelper;
import io.virbius.control.domain.enums.RuleStatus;
import io.virbius.control.domain.enums.AccessListDimension;
import io.virbius.control.domain.enums.EnforceMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Primary
@Repository
public class JdbcRegistryRepository implements RegistryRepository {

    private final JdbcTemplate jdbc;

    public JdbcRegistryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<BundleVersion> listBundles(String tenantId) {
        return jdbc.query(
                """
                SELECT tenant_id, bundle_id, version, status, publish_id, sync_ack_json, metadata_json
                FROM tb_bundles WHERE tenant_id = ?
                ORDER BY bundle_id, version
                """,
                bundleMapper(),
                tenantId);
    }

    @Override
    public Optional<BundleVersion> getBundle(String tenantId, String bundleId, String version) {
        List<BundleVersion> rows = jdbc.query(
                """
                SELECT tenant_id, bundle_id, version, status, publish_id, sync_ack_json, metadata_json
                FROM tb_bundles WHERE tenant_id = ? AND bundle_id = ? AND version = ?
                """,
                bundleMapper(),
                tenantId,
                bundleId,
                version);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public BundleVersion createBundle(String tenantId, String bundleId) {
        Optional<BundleVersion> existing = getBundle(tenantId, bundleId, "0.1.0");
        if (existing.isPresent()) {
            return existing.get();
        }
        String metadataJson =
                "poc-default".equals(bundleId) ? JsonHelper.toJson(BundleMetadataResources.pocDefault010()) : "{}";
        try {
            jdbc.update(
                    """
                    INSERT INTO tb_bundles (tenant_id, bundle_id, version, status, metadata_json)
                    VALUES (?, ?, '0.1.0', 'draft', ?)
                    """,
                    tenantId,
                    bundleId,
                    metadataJson);
        } catch (DuplicateKeyException ignored) {
        }
        return getBundle(tenantId, bundleId, "0.1.0").orElseThrow();
    }

    @Override
    @Transactional
    public RuleRevision upsertRule(String tenantId, RuleRevision draft) {
        String ruleId = draft.ruleId();
        int next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(rule_revision), 0) + 1 FROM tb_rule_history WHERE tenant_id = ? AND rule_id = ?",
                Integer.class,
                tenantId,
                ruleId);
        String nowStr = TimeHelper.nowIso();

        jdbc.update(
                "UPDATE tb_rule_history SET effective_to = ? WHERE tenant_id = ? AND rule_id = ? AND effective_to IS NULL",
                nowStr,
                tenantId,
                ruleId);

        String bodyJson = JsonHelper.toJson(draft.body());
        String scopeJson = JsonHelper.toJson(draft.scope());

        jdbc.update(
                """
                INSERT INTO tb_rule_history (
                  tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, scope_json, body_json,
                  enforce_mode, canary_percent, rule_status, effective_from, modified_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId,
                ruleId,
                next,
                draft.bundleId() != null ? draft.bundleId() : "poc-default",
                draft.layer(),
                draft.runtime(),
                draft.reasonCode(),
                RiskScore.normalize(draft.riskScore()),
                draft.intentAction() != null
                        ? draft.intentAction()
                        : io.virbius.policy.IntentAction.defaultForRisk(draft.riskScore()),
                scopeJson,
                bodyJson,
                draft.enforceMode() != null ? draft.enforceMode() : "dry_run",
                draft.canaryPercent(),
                draft.ruleStatus() != null ? draft.ruleStatus() : RuleStatus.DRAFT.value(),
                nowStr,
                nowStr);

        upsertRulesCurrent(tenantId, ruleId, next, draft, nowStr);

        return getRuleRevision(tenantId, ruleId, next).orElseThrow();
    }

    private void upsertRulesCurrent(String tenantId, String ruleId, int revision, RuleRevision draft, String nowStr) {
        int updated = jdbc.update(
                """
                UPDATE tb_rules_current SET
                  current_revision = ?, bundle_id = ?, layer = ?, runtime = ?,
                  reason_code = ?, intent_action = ?, enforce_mode = ?, rule_status = ?, updated_at = ?
                WHERE tenant_id = ? AND rule_id = ?
                """,
                revision,
                draft.bundleId() != null ? draft.bundleId() : "poc-default",
                draft.layer(),
                draft.runtime(),
                draft.reasonCode(),
                draft.intentAction() != null
                        ? draft.intentAction()
                        : io.virbius.policy.IntentAction.defaultForRisk(draft.riskScore()),
                draft.enforceMode() != null ? draft.enforceMode() : "dry_run",
                draft.ruleStatus() != null ? draft.ruleStatus() : RuleStatus.DRAFT.value(),
                nowStr,
                tenantId,
                ruleId);
        if (updated > 0) {
            return;
        }
        try {
            jdbc.update(
                    """
                    INSERT INTO tb_rules_current (
                      tenant_id, rule_id, current_revision, bundle_id, layer, runtime, reason_code, intent_action, enforce_mode, rule_status, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    tenantId,
                    ruleId,
                    revision,
                    draft.bundleId() != null ? draft.bundleId() : "poc-default",
                    draft.layer(),
                    draft.runtime(),
                    draft.reasonCode(),
                    draft.intentAction() != null
                            ? draft.intentAction()
                            : io.virbius.policy.IntentAction.defaultForRisk(draft.riskScore()),
                    draft.enforceMode() != null ? draft.enforceMode() : "dry_run",
                    draft.ruleStatus() != null ? draft.ruleStatus() : RuleStatus.DRAFT.value(),
                    nowStr);
        } catch (DuplicateKeyException e) {
            upsertRulesCurrent(tenantId, ruleId, revision, draft, nowStr);
        }
    }

    @Override
    public List<RuleRevision> listCurrentRules(String tenantId, String layer) {
        List<RuleRevision> out = new ArrayList<>();
        for (String ruleId :
                jdbc.queryForList("SELECT rule_id FROM tb_rules_current WHERE tenant_id = ?", String.class, tenantId)) {
            getCurrentRule(tenantId, ruleId).ifPresent(r -> {
                if (layer == null || layer.isBlank() || layer.equals(r.layer())) {
                    out.add(r);
                }
            });
        }
        return out.stream().sorted(Comparator.comparing(RuleRevision::ruleId)).toList();
    }

    @Override
    public Optional<RuleRevision> getRuleRevision(String tenantId, String ruleId, int revision) {
        List<RuleRevision> rows = jdbc.query(
                "SELECT * FROM tb_rule_history WHERE tenant_id = ? AND rule_id = ? AND rule_revision = ?",
                ruleMapper(),
                tenantId,
                ruleId,
                revision);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public RuleRevision updateRuntime(String tenantId, String ruleId, String enforceMode, Integer canaryPercent) {
        EnforceMode.parse(enforceMode);
        RuleRevision current = getCurrentRule(tenantId, ruleId).orElseThrow();
        RuleStatusHelper.requireActive(current);
        RuleRevision draft = new RuleRevision(
                tenantId,
                ruleId,
                0,
                current.bundleId(),
                current.layer(),
                current.runtime(),
                current.reasonCode(),
                current.riskScore(),
                current.intentAction(),
                current.scope(),
                current.body(),
                enforceMode,
                canaryPercent,
                current.ruleStatus(),
                null,
                null,
                null);
        return upsertRule(tenantId, draft);
    }

    @Override
    public RuleRevision updateRuleStatus(String tenantId, String ruleId, String ruleStatus) {
        String normalized = RuleStatusHelper.normalize(ruleStatus);
        RuleRevision current = getCurrentRule(tenantId, ruleId).orElseThrow();
        if (normalized.equals(RuleStatusHelper.statusOf(current))) {
            return current;
        }
        RuleRevision draft = new RuleRevision(
                tenantId,
                ruleId,
                0,
                current.bundleId(),
                current.layer(),
                current.runtime(),
                current.reasonCode(),
                current.riskScore(),
                current.intentAction(),
                current.scope(),
                current.body(),
                current.enforceMode(),
                current.canaryPercent(),
                normalized,
                null,
                null,
                null);
        return upsertRule(tenantId, draft);
    }

    @Override
    public List<RuleRevision> listRuleRevisions(String tenantId, String ruleId) {
        return jdbc.query(
                """
                SELECT * FROM tb_rule_history
                WHERE tenant_id = ? AND rule_id = ?
                ORDER BY rule_revision
                """,
                ruleMapper(),
                tenantId,
                ruleId);
    }

    @Override
    public Optional<RuleRevision> getCurrentRule(String tenantId, String ruleId) {
        List<Integer> revs = jdbc.query(
                "SELECT current_revision FROM tb_rules_current WHERE tenant_id = ? AND rule_id = ?",
                (rs, i) -> rs.getInt(1),
                tenantId,
                ruleId);
        if (revs.isEmpty()) {
            return Optional.empty();
        }
        return getRuleRevision(tenantId, ruleId, revs.get(0));
    }

    @Override
    public void updateBundleMetadata(
            String tenantId, String bundleId, String version, Map<String, Object> metadata) {
        jdbc.update(
                """
                UPDATE tb_bundles SET metadata_json = ?, updated_at = ?
                WHERE tenant_id = ? AND bundle_id = ? AND version = ?
                """,
                JsonHelper.toJson(metadata != null ? metadata : Map.of()),
                TimeHelper.nowIso(),
                tenantId,
                bundleId,
                version);
    }

    @Override
    public void updateBundleStatus(
            String tenantId, String bundleId, String version, String status, String publishId, Object syncAck) {
        String syncJson;
        try {
            syncJson = syncAck == null ? null : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(syncAck);
        } catch (Exception e) {
            syncJson = "{}";
        }
        jdbc.update(
                """
                UPDATE tb_bundles SET status = ?, publish_id = ?, sync_ack_json = ?, updated_at = ?
                WHERE tenant_id = ? AND bundle_id = ? AND version = ?
                """,
                status,
                publishId,
                syncJson,
                TimeHelper.nowIso(),
                tenantId,
                bundleId,
                version);
    }

    private RowMapper<BundleVersion> bundleMapper() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        return (rs, rowNum) -> {
            String syncJson = rs.getString("sync_ack_json");
            Map<String, Object> syncAck = Map.of();
            if (syncJson != null && !syncJson.isBlank()) {
                try {
                    syncAck = mapper.readValue(syncJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
                } catch (Exception ignored) {
                    syncAck = Map.of();
                }
            }
            return new BundleVersion(
                    rs.getString("tenant_id"),
                    rs.getString("bundle_id"),
                    rs.getString("version"),
                    rs.getString("status"),
                    rs.getString("publish_id"),
                    syncAck,
                    JsonHelper.mapFromJson(rs.getString("metadata_json")));
        };
    }

    private RowMapper<RuleRevision> ruleMapper() {
        return (rs, rowNum) -> mapRule(rs);
    }

    private RuleRevision mapRule(ResultSet rs) throws SQLException {
        int risk = rs.getInt("risk_score");
        String intent = rs.getString("intent_action");
        if (intent == null || intent.isBlank()) {
            intent = io.virbius.policy.IntentAction.defaultForRisk(risk);
        }
        return new RuleRevision(
                rs.getString("tenant_id"),
                rs.getString("rule_id"),
                rs.getInt("rule_revision"),
                rs.getString("bundle_id"),
                rs.getString("layer"),
                rs.getString("runtime"),
                rs.getString("reason_code"),
                risk,
                intent,
                JsonHelper.mapFromJson(rs.getString("scope_json")),
                JsonHelper.bodyFromJson(rs.getString("body_json")),
                rs.getString("enforce_mode"),
                (Integer) rs.getObject("canary_percent"),
                rs.getString("rule_status"),
                TimeHelper.parseInstant(rs.getString("modified_at")),
                TimeHelper.parseInstant(rs.getString("effective_from")),
                TimeHelper.parseInstant(rs.getString("effective_to")));
    }
}
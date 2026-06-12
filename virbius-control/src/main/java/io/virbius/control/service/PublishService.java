package io.virbius.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.domain.BundleVersion;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.RolloutStateHelper;
import io.virbius.control.groovy.GroovyRuleValidator;
import io.virbius.control.policy.PolicyDataBuilder;
import io.virbius.control.policy.PolicyMaterializer;
import io.virbius.control.repository.RegistryRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PublishService {

    private static final Logger log = LoggerFactory.getLogger(PublishService.class);

    private final RegistryRepository store;
    private final GroovyRuleValidator groovyRuleValidator;
    private final PolicyMaterializer policyMaterializer;
    private final PolicyDataBuilder policyDataBuilder;
    private final CacheReloadNotifier reloadNotifier;
    private final ObjectMapper mapper = new ObjectMapper();

    public PublishService(
            RegistryRepository store,
            GroovyRuleValidator groovyRuleValidator,
            PolicyMaterializer policyMaterializer,
            PolicyDataBuilder policyDataBuilder,
            CacheReloadNotifier reloadNotifier) {
        this.store = store;
        this.groovyRuleValidator = groovyRuleValidator;
        this.policyMaterializer = policyMaterializer;
        this.policyDataBuilder = policyDataBuilder;
        this.reloadNotifier = reloadNotifier;
    }

    public Map<String, Object> publish(String tenantId, String bundleId, String version) {
        BundleVersion bundle = store.getBundle(tenantId, bundleId, version)
                .orElseThrow(() -> new IllegalArgumentException("bundle not found"));
        if ("active".equals(bundle.status())) {
            throw new IllegalStateException("bundle already active");
        }
        String publishId = UUID.randomUUID().toString();
        for (String status : List.of("validating", "eval_running", "compiling", "syncing")) {
            store.updateBundleStatus(tenantId, bundleId, version, status, publishId, Map.of());
        }
        List<RuleRevision> rules = store.listCurrentRules(tenantId, null);
        for (RuleRevision r : rules) {
            if (!RolloutStateHelper.inExecutionPlane(r)) {
                continue;
            }
            try {
                groovyRuleValidator.validateRevision(r);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("groovy validation failed for " + r.ruleId() + ": " + e.getMessage());
            }
        }
        boolean hasGroovy = rules.stream()
                .anyMatch(r -> RolloutStateHelper.inExecutionPlane(r) && "groovy".equals(r.runtime()));
        if (!hasGroovy) {
            throw new IllegalStateException("publish requires at least one active runtime=groovy rule");
        }
        Map<String, Object> syncAck = reloadEngineCache(tenantId, version);
        store.updateBundleStatus(tenantId, bundleId, version, "active", publishId, syncAck);
        return Map.of(
                "bundle_id", bundleId,
                "bundle_version", version,
                "status", "active",
                "publish_id", publishId,
                "sync_ack", syncAck);
    }

    public Map<String, Object> status(String tenantId, String bundleId, String version) {
        BundleVersion bundle = store.getBundle(tenantId, bundleId, version)
                .orElseThrow(() -> new IllegalArgumentException("bundle not found"));
        return Map.of(
                "bundle_id", bundle.bundleId(),
                "bundle_version", bundle.version(),
                "status", bundle.status(),
                "publish_id", bundle.publishId() != null ? bundle.publishId() : "",
                "sync_ack", bundle.syncAck() != null ? bundle.syncAck() : Map.of());
    }

    public Map<String, Object> runtimeSnapshot(String tenantId) {
        Map<String, Object> syncAck = reloadEngineCache(tenantId, "runtime-only");
        return Map.of("accepted", true, "sync_ack", syncAck);
    }

    public Map<String, Object> buildRuntimeSnapshotPayload(String tenantId) {
        List<RuleRevision> rules = store.listCurrentRules(tenantId, "cloud").stream()
                .filter(RolloutStateHelper::inExecutionPlane)
                .map(r -> policyMaterializer.materialize(tenantId, r))
                .toList();
        ObjectMapper snakeMapper = new ObjectMapper()
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        com.fasterxml.jackson.databind.node.ObjectNode body = snakeMapper.createObjectNode();
        body.put("policy_version", "runtime-only");
        com.fasterxml.jackson.databind.node.ArrayNode rulesNode = body.putArray("rules");
        for (RuleRevision r : rules) {
            if (!"cloud".equals(r.layer()) || !RolloutStateHelper.inExecutionPlane(r)) {
                continue;
            }
            com.fasterxml.jackson.databind.node.ObjectNode rule = rulesNode.addObject();
            rule.put("tenant_id", r.tenantId());
            rule.put("rule_id", r.ruleId());
            rule.put("rule_revision", r.ruleRevision());
            rule.put("layer", r.layer());
            rule.put("runtime", r.runtime());
            rule.put("reason_code", r.reasonCode());
            rule.put("risk_score", r.riskScore());
            rule.put("intent_action", r.intentAction() != null ? r.intentAction() : "deny");
            rule.put("enforce_mode", r.enforceMode());
            rule.put("rollout_state", r.rolloutState() != null ? r.rolloutState() : "dry_run");
            rule.put("is_async", r.isAsync());
            if (r.asyncActionConfig() != null && !r.asyncActionConfig().isBlank()) {
                rule.put("async_action_config", r.asyncActionConfig());
            }
            if (r.exportedCanaryPercent() != null) {
                rule.put("canary_percent", r.exportedCanaryPercent());
            }
            if (r.scope() != null && !r.scope().isEmpty()) {
                rule.set("scope", snakeMapper.valueToTree(r.scope()));
            }
            if (r.body() != null) {
                if (r.body() instanceof String s) {
                    rule.put("body", s);
                } else {
                    try {
                        rule.put("body", snakeMapper.writeValueAsString(r.body()));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new RuntimeException("Failed to serialize rule body for " + r.ruleId(), e);
                    }
                }
            }
        }
        body.set("lists", snakeMapper.valueToTree(policyDataBuilder.buildEngineMemoryLists(tenantId)));
        body.set("redis_list_index", snakeMapper.valueToTree(policyDataBuilder.buildEngineRedisListIndex(tenantId)));
        body.set("cumulatives", snakeMapper.valueToTree(policyDataBuilder.buildEngineCumulatives(tenantId)));
        return snakeMapper.convertValue(body, Map.class);
    }

    private Map<String, Object> reloadEngineCache(String tenantId, String bundleVersion) {
        try {
            Map<String, Object> payload = buildRuntimeSnapshotPayload(tenantId);
            payload.put("policy_version", bundleVersion);
            return reloadNotifier.publish(tenantId, bundleVersion, payload);
        } catch (Exception e) {
            log.warn("engine reload error: {}", e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}
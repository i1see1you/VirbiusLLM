package io.virbius.control.service;

import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.RuleStatusHelper;
import io.virbius.control.domain.RiskScore;
import io.virbius.control.domain.enums.AccessListDimension;
import io.virbius.control.domain.enums.AccessListPolarity;
import io.virbius.control.repository.AccessListRepository;
import io.virbius.control.repository.RegistryRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AccessListService {

    private final AccessListRepository listRepo;
    private final RegistryRepository registryRepo;
    private final PublishService publishService;
    private final ArtifactService artifactService;

    public AccessListService(
            AccessListRepository listRepo,
            RegistryRepository registryRepo,
            PublishService publishService,
            ArtifactService artifactService) {
        this.listRepo = listRepo;
        this.registryRepo = registryRepo;
        this.publishService = publishService;
        this.artifactService = artifactService;
    }

    public Map<String, Object> getAll(String tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant_id", tenantId);
        out.put("lists", listRepo.listAll(tenantId));
        out.put("blacklist", listRepo.list(tenantId, AccessListPolarity.DENY, AccessListDimension.KEYWORD));
        out.put("whitelist", listRepo.list(tenantId, AccessListPolarity.ALLOW, AccessListDimension.KEYWORD));
        return out;
    }

    public List<String> get(String tenantId, AccessListPolarity polarity, AccessListDimension dimension) {
        return listRepo.list(tenantId, polarity, dimension);
    }

    public Map<String, Object> replaceAndPush(
            String tenantId, AccessListPolarity polarity, AccessListDimension dimension, List<String> values) {
        listRepo.replaceAll(tenantId, polarity, dimension, normalizeValues(tenantId, dimension, values));
        return syncRulesAndPush(tenantId, Map.of());
    }

    public Map<String, Object> addEntriesAndPush(
            String tenantId, AccessListPolarity polarity, AccessListDimension dimension, List<String> values) {
        int added = 0;
        for (String v : values) {
            if (listRepo.add(
                    tenantId,
                    polarity,
                    dimension,
                    AccessListEntryValidator.normalizeAndValidate(dimension, v, bundleMetadata(tenantId)))) {
                added++;
            }
        }
        return syncRulesAndPush(tenantId, Map.of("added", added));
    }

    public Map<String, Object> removeEntryAndPush(
            String tenantId, AccessListPolarity polarity, AccessListDimension dimension, String value) {
        boolean removed = listRepo.remove(tenantId, polarity, dimension, value);
        return syncRulesAndPush(tenantId, Map.of("removed", removed));
    }

    public Map<String, Object> pushToEngine(String tenantId) {
        return Map.of("engine_reload", publishService.runtimeSnapshot(tenantId));
    }

    public Map<String, Object> syncRules(String tenantId) {
        List<String> denyKw = listRepo.list(tenantId, AccessListPolarity.DENY, AccessListDimension.KEYWORD);
        List<String> allowKw = listRepo.list(tenantId, AccessListPolarity.ALLOW, AccessListDimension.KEYWORD);

        syncProjectedRule(tenantId, AccessListRules.contentRule(
                tenantId, AccessListRules.EDGE_CONTENT_DENY, "edge", "lua-dsl",
                AccessListRules.REASON_EDGE_CONTENT_DENY, RiskScore.DEFAULT, denyKw, "deny"));
        syncProjectedRule(tenantId, AccessListRules.contentRule(
                tenantId, AccessListRules.EDGE_CONTENT_ALLOW, "edge", "lua-dsl",
                AccessListRules.REASON_EDGE_CONTENT_ALLOW, RiskScore.ALLOW, allowKw, "allow"));

        syncProjectedRule(tenantId, AccessListRules.gatewaySubjectRule(tenantId, AccessListPolarity.DENY,
                listRepo.list(tenantId, AccessListPolarity.DENY, AccessListDimension.USER_ID),
                listRepo.list(tenantId, AccessListPolarity.DENY, AccessListDimension.DEVICE_ID),
                listRepo.list(tenantId, AccessListPolarity.DENY, AccessListDimension.IP_CIDR)));
        syncProjectedRule(tenantId, AccessListRules.gatewaySubjectRule(tenantId, AccessListPolarity.ALLOW,
                listRepo.list(tenantId, AccessListPolarity.ALLOW, AccessListDimension.USER_ID),
                listRepo.list(tenantId, AccessListPolarity.ALLOW, AccessListDimension.DEVICE_ID),
                listRepo.list(tenantId, AccessListPolarity.ALLOW, AccessListDimension.IP_CIDR)));
        syncProjectedRule(tenantId, AccessListRules.gatewayContentRule(
                tenantId, AccessListRules.GW_CONTENT_DENY, AccessListRules.REASON_GW_KEYWORD_DENY,
                RiskScore.DEFAULT, denyKw, "deny"));
        syncProjectedRule(tenantId, AccessListRules.gatewayContentRule(
                tenantId, AccessListRules.GW_CONTENT_ALLOW, AccessListRules.REASON_CLOUD_CONTENT_ALLOW,
                RiskScore.ALLOW, allowKw, "allow"));

        syncProjectedRule(tenantId, AccessListRules.contentRule(
                tenantId, AccessListRules.CLOUD_CONTENT_DENY, "cloud", "native",
                AccessListRules.REASON_CLOUD_CONTENT_DENY, RiskScore.DEFAULT, denyKw, "deny"));
        syncProjectedRule(tenantId, AccessListRules.contentRule(
                tenantId, AccessListRules.CLOUD_CONTENT_ALLOW, "cloud", "native",
                AccessListRules.REASON_CLOUD_CONTENT_ALLOW, RiskScore.ALLOW, allowKw, "allow"));

        List<String> denyVars = listRepo.list(tenantId, AccessListPolarity.DENY, AccessListDimension.VAR);
        List<String> allowVars = listRepo.list(tenantId, AccessListPolarity.ALLOW, AccessListDimension.VAR);

        syncProjectedRule(tenantId, AccessListRules.contextVarRule(
                tenantId, AccessListRules.GW_REQUEST_DENY, "gateway", "lua",
                AccessListRules.REASON_GW_CONTEXT_VAR_DENY, RiskScore.DEFAULT, denyVars, "deny"));
        syncProjectedRule(tenantId, AccessListRules.contextVarRule(
                tenantId, AccessListRules.GW_REQUEST_ALLOW, "gateway", "lua",
                AccessListRules.REASON_GW_CONTEXT_VAR_DENY, RiskScore.ALLOW, allowVars, "allow"));
        syncProjectedRule(tenantId, AccessListRules.contextVarRule(
                tenantId, AccessListRules.CLOUD_REQUEST_DENY, "cloud", "native",
                AccessListRules.REASON_CLOUD_CONTEXT_VAR_DENY, RiskScore.DEFAULT, denyVars, "deny"));
        syncProjectedRule(tenantId, AccessListRules.contextVarRule(
                tenantId, AccessListRules.CLOUD_REQUEST_ALLOW, "cloud", "native",
                AccessListRules.REASON_CLOUD_CONTEXT_VAR_DENY, RiskScore.ALLOW, allowVars, "allow"));

        Map<String, Object> metadata = bundleMetadata(tenantId);
        Map<String, String> artifacts = artifactService.write(tenantId, listRepo, metadata);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("synced", true);
        summary.put("artifacts", artifacts);
        summary.put("deny_keyword_count", denyKw.size());
        summary.put("allow_keyword_count", allowKw.size());
        summary.put("deny_user_count", listRepo.list(tenantId, AccessListPolarity.DENY, AccessListDimension.USER_ID).size());
        summary.put("deny_device_count", listRepo.list(tenantId, AccessListPolarity.DENY, AccessListDimension.DEVICE_ID).size());
        summary.put("deny_ip_count", listRepo.list(tenantId, AccessListPolarity.DENY, AccessListDimension.IP_CIDR).size());
        summary.put("deny_var_count", denyVars.size());
        summary.put("allow_var_count", allowVars.size());
        summary.put("rule_ids", AccessListRules.allRuleIds());
        return summary;
    }

    public Map<String, Object> syncAndPublish(String tenantId, String bundleId, String version) {
        Map<String, Object> sync = new LinkedHashMap<>(syncRules(tenantId));
        if (registryRepo.listBundles(tenantId).isEmpty()) {
            registryRepo.createBundle(tenantId, bundleId);
        }
        try {
            sync.put("publish", publishService.publish(tenantId, bundleId, version));
        } catch (IllegalStateException alreadyActive) {
            sync.put("engine_reload", publishService.runtimeSnapshot(tenantId));
        }
        return sync;
    }

    private void syncProjectedRule(String tenantId, RuleRevision draft) {
        Optional<RuleRevision> current = registryRepo.getCurrentRule(tenantId, draft.ruleId());
        if (current.isPresent() && !RuleStatusHelper.isActive(current.get())) {
            return;
        }
        registryRepo.upsertRule(tenantId, draft);
    }

    private Map<String, Object> bundleMetadata(String tenantId) {
        Optional<io.virbius.control.domain.BundleVersion> bundle =
                registryRepo.getBundle(tenantId, AccessListRules.DEFAULT_BUNDLE, "0.1.0");
        return bundle.map(b -> b.metadata() != null ? b.metadata() : Map.<String, Object>of())
                .orElse(Map.of());
    }

    private Map<String, Object> syncRulesAndPush(String tenantId, Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>(syncRules(tenantId));
        out.putAll(extra);
        out.put("engine_reload", publishService.runtimeSnapshot(tenantId));
        return out;
    }

    private List<String> normalizeValues(String tenantId, AccessListDimension dimension, List<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        Map<String, Object> metadata = bundleMetadata(tenantId);
        for (String v : values) {
            out.add(AccessListEntryValidator.normalizeAndValidate(dimension, v, metadata));
        }
        return out;
    }
}
package io.virbius.control.service;

import io.virbius.control.domain.CumulativeDef;
import io.virbius.control.domain.AccessListMeta;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.RolloutStateHelper;
import io.virbius.control.domain.TenantRolloutPolicy;
import io.virbius.control.groovy.GroovyRuleBodies;
import io.virbius.control.policy.BindScopeExport;
import io.virbius.control.policy.RuleBodyRefs;
import io.virbius.control.repository.CumulativeRepository;
import io.virbius.control.repository.ListMetaRepository;
import io.virbius.control.repository.RegistryRepository;
import io.virbius.control.repository.TenantRolloutPolicyRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private final java.nio.file.Path dataDir;
    private final RegistryRepository registryRepo;
    private final ListMetaRepository listMetaRepo;
    private final CumulativeRepository cumulativeRepo;
    private final TenantRolloutPolicyRepository policyRepository;
    private final String auditIngestUrl;
    private final String auditIngestToken;

    public ArtifactService(
            @Value("${virbius.data-dir:./data}") String dataDir,
            RegistryRepository registryRepo,
            ListMetaRepository listMetaRepo,
            CumulativeRepository cumulativeRepo,
            TenantRolloutPolicyRepository policyRepository,
            @Value("${audit.edge.ingest-url:}") String auditIngestUrl,
            @Value("${audit.ingest.http.token:}") String auditIngestToken) {
        this.dataDir = java.nio.file.Path.of(dataDir);
        this.registryRepo = registryRepo;
        this.listMetaRepo = listMetaRepo;
        this.cumulativeRepo = cumulativeRepo;
        this.policyRepository = policyRepository;
        this.auditIngestUrl = auditIngestUrl != null ? auditIngestUrl : "";
        this.auditIngestToken = auditIngestToken != null ? auditIngestToken : "";
    }

    public Map<String, String> write(String tenantId, Map<String, Object> bundleMetadata) {
        Map<String, String> paths = new LinkedHashMap<>();
        try {
            paths.put("gateway", writeGateway(tenantId, bundleMetadata).toString());
            paths.put("edge", writeEdge(tenantId).toString());
        } catch (Exception e) {
            log.warn("failed to write list artifacts: {}", e.getMessage());
            paths.put("error", e.getMessage());
        }
        return paths;
    }

    private java.nio.file.Path writeGateway(String tenantId, Map<String, Object> bundleMetadata) throws Exception {
        java.nio.file.Path dir = dataDir.resolve("gateway");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Path file = dir.resolve(tenantId + "-access-lists.json");
        Map<String, Object> root = buildGatewaySnapshot(tenantId, bundleMetadata);
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        return file;
    }

    /** Package-visible for unit tests. */
    Map<String, Object> buildGatewaySnapshot(String tenantId) {
        return buildGatewaySnapshot(tenantId, Map.of());
    }

    Map<String, Object> buildGatewaySnapshot(String tenantId, Map<String, Object> bundleMetadata) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tenant_id", tenantId);
        root.put("lists", buildListDefBlocks(tenantId));
        root.put("cumulatives", buildCumulativeDefBlocks(tenantId));
        root.put("script_rules", buildScriptRuleBlocks(tenantId));
        Map<String, Object> bindings = ContextBindingsHelper.bindingsBlock(bundleMetadata);
        if (!bindings.isEmpty()) {
            root.put("context_bindings", bindings);
        }
        return root;
    }

    private List<Map<String, Object>> buildListDefBlocks(String tenantId) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (AccessListMeta meta : listMetaRepo.listMeta(tenantId)) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("list_name", meta.listName());
            block.put("dimension", meta.dimension());
            if (meta.remark() != null && !meta.remark().isBlank()) {
                block.put("remark", meta.remark());
            }
            block.put("entries", AccessListService.entryMaps(listMetaRepo.listEntries(tenantId, meta.listName())));
            blocks.add(block);
        }
        blocks.sort((a, b) -> String.valueOf(a.get("list_name")).compareTo(String.valueOf(b.get("list_name"))));
        return blocks;
    }

    private List<Map<String, Object>> buildCumulativeDefBlocks(String tenantId) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (CumulativeDef def : cumulativeRepo.list(tenantId, "active")) {
            List<RuleRevision> bindings = listScriptBindingRules(tenantId, def.cumulativeName());
            List<BindScopeExport.RuleBindingSource> sources = new ArrayList<>();
            List<Map<String, Object>> bindingRules = new ArrayList<>();
            for (RuleRevision rule : bindings) {
                RuleBodyRefs refs = RuleBodyRefs.parse(rule.body());
                sources.add(new BindScopeExport.RuleBindingSource(refs.valueSource(), rule.scope()));
                Map<String, Object> entry = BindScopeExport.bindEntry(rule.scope());
                if (!bindingRules.contains(entry)) {
                    bindingRules.add(entry);
                }
            }
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("cumulative_name", def.cumulativeName());
            block.put("dimension", def.dimension());
            block.put("window_kind", def.windowKind());
            block.put("window_minutes", def.windowMinutes());
            block.put("window_hours", def.windowHours());
            block.put("timezone", def.timezone());
            block.put("priority", def.priority());
            block.put("ingest_targets", BindScopeExport.ingestTargetsFromRules(sources));
            if (!bindingRules.isEmpty()) {
                block.put("binding_rules", bindingRules);
            } else {
                block.put("binding_rules", List.of(Map.of("bind_scope", "global")));
            }
            blocks.add(block);
        }
        blocks.sort((a, b) -> Integer.compare((int) b.get("priority"), (int) a.get("priority")));
        return blocks;
    }

    private List<Map<String, Object>> buildScriptRuleBlocks(String tenantId) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (RuleRevision rule : registryRepo.listCurrentRules(tenantId, "gateway")) {
            if (!RolloutStateHelper.inExecutionPlane(rule) || !"lua".equals(rule.runtime())) {
                continue;
            }
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("rule_id", rule.ruleId());
            block.put("rule_revision", rule.ruleRevision());
            block.put("reason_code", rule.reasonCode());
            block.put("risk_score", rule.riskScore());
            block.put("intent_action", rule.intentAction() != null ? rule.intentAction() : "deny");
            block.put("enforce_mode", rule.enforceMode());
            if (rule.canaryPercent() != null) {
                block.put("canary_percent", rule.canaryPercent());
            } else if (rule.exportedCanaryPercent() != null) {
                block.put("canary_percent", rule.exportedCanaryPercent());
            }
            block.put("body", GroovyRuleBodies.asScript(rule.body()));
            BindScopeExport.putBindFields(block, rule.scope());
            blocks.add(block);
        }
        blocks.sort((a, b) -> Integer.compare((int) b.get("risk_score"), (int) a.get("risk_score")));
        return blocks;
    }

    private List<RuleRevision> listScriptBindingRules(String tenantId, String cumulativeName) {
        List<RuleRevision> out = new ArrayList<>();
        for (RuleRevision rule : registryRepo.listCurrentRules(tenantId, null)) {
            if (!RolloutStateHelper.inExecutionPlane(rule)) {
                continue;
            }
            if (!"lua".equals(rule.runtime()) && !"groovy".equals(rule.runtime())) {
                continue;
            }
            String body = GroovyRuleBodies.asScript(rule.body());
            if (!referencesCumulative(body, cumulativeName)) {
                continue;
            }
            out.add(rule);
        }
        return out;
    }

    private static boolean referencesCumulative(String body, String cumulativeName) {
        if (body == null || cumulativeName == null || cumulativeName.isBlank()) {
            return false;
        }
        return body.contains(cumulativeName)
                && (body.contains("getCumulative") || body.contains("getCumulative("));
    }

    private java.nio.file.Path writeEdge(String tenantId) throws Exception {
        java.nio.file.Path dir = dataDir.resolve("edge");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Path manifestFile = dir.resolve(tenantId + "-edge-manifest.json");
        java.nio.file.Path legacyFile = dir.resolve(tenantId + "-content-lists.json");
        TenantRolloutPolicy policy = policyRepository.getOrDefault(tenantId);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tenant_id", tenantId);
        root.put("manifest_version", "1");
        root.put("rules", buildEdgeRuleBlocks(tenantId));

        Map<String, Object> sdk = new LinkedHashMap<>();
        if (!auditIngestUrl.isBlank()) {
            sdk.put("audit_ingest_url", auditIngestUrl);
        }
        if (!auditIngestToken.isBlank()) {
            sdk.put("audit_ingest_token", auditIngestToken);
        }
        sdk.put("audit_sample_rate_allow", policy.edgeAuditSampleRateAllow());
        sdk.put("audit_sample_rate_hit", 1.0);
        sdk.put("audit_flush_interval_ms", 30000);
        sdk.put("audit_queue_max", 500);
        sdk.put("canary_session_key", "device_id");
        root.put("sdk_config", sdk);

        Map<String, Object> legacyLists = new LinkedHashMap<>();
        legacyLists.put("tenant_id", tenantId);
        legacyLists.put("deny", Map.of("keywords", collectEdgeDenyKeywords(tenantId)));
        legacyLists.put("allow", Map.of("keywords", collectEdgeAllowKeywords(tenantId)));
        root.put("lists", legacyLists);

        mapper.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), root);
        mapper.writerWithDefaultPrettyPrinter().writeValue(legacyFile.toFile(), legacyLists);
        return manifestFile;
    }

    private List<Map<String, Object>> buildEdgeRuleBlocks(String tenantId) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (RuleRevision rule : registryRepo.listCurrentRules(tenantId, "edge")) {
            if (!RolloutStateHelper.inExecutionPlane(rule)) {
                continue;
            }
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("rule_id", rule.ruleId());
            block.put("rule_revision", rule.ruleRevision());
            block.put("reason_code", rule.reasonCode());
            block.put("risk_score", rule.riskScore());
            block.put("intent_action", rule.intentAction() != null ? rule.intentAction() : "deny");
            block.put("enforce_mode", rule.enforceMode());
            block.put("rollout_state", rule.rolloutState() != null ? rule.rolloutState() : "dry_run");
            if (rule.exportedCanaryPercent() != null) {
                block.put("canary_percent", rule.exportedCanaryPercent());
            } else if (rule.canaryPercent() != null) {
                block.put("canary_percent", rule.canaryPercent());
            }
            if (rule.body() != null) {
                block.put("body", rule.body());
            }
            blocks.add(block);
        }
        blocks.sort((a, b) -> String.valueOf(a.get("rule_id")).compareTo(String.valueOf(b.get("rule_id"))));
        return blocks;
    }

    @SuppressWarnings("unchecked")
    private List<String> collectEdgeDenyKeywords(String tenantId) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> block : buildEdgeRuleBlocks(tenantId)) {
            Object body = block.get("body");
            if (!(body instanceof Map<?, ?> bodyMap)) {
                continue;
            }
            if (!"deny".equals(String.valueOf(bodyMap.get("list_type")))) {
                continue;
            }
            Object keywords = bodyMap.get("keywords");
            if (keywords instanceof List<?> list) {
                for (Object kw : list) {
                    if (kw != null) {
                        out.add(kw.toString());
                    }
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> collectEdgeAllowKeywords(String tenantId) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> block : buildEdgeRuleBlocks(tenantId)) {
            Object body = block.get("body");
            if (!(body instanceof Map<?, ?> bodyMap)) {
                continue;
            }
            if (!"allow".equals(String.valueOf(bodyMap.get("list_type")))) {
                continue;
            }
            Object keywords = bodyMap.get("keywords");
            if (keywords instanceof List<?> list) {
                for (Object kw : list) {
                    if (kw != null) {
                        out.add(kw.toString());
                    }
                }
            }
        }
        return out;
    }
}

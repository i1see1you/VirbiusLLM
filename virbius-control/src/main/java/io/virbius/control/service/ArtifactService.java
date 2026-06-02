package io.virbius.control.service;

import io.virbius.control.domain.AccessListEntry;
import io.virbius.control.domain.AccessListMeta;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.RolloutStateHelper;
import io.virbius.control.domain.TenantRolloutPolicy;
import io.virbius.control.policy.RuleBodyRefs;
import io.virbius.policy.RuleCondition;
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
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tenant_id", tenantId);
        root.put("lists", buildListBlocks(tenantId));
        root.put("cumulative_rules", buildCumulativeRuleBlocks(tenantId));

        Map<String, Object> bindings = ContextBindingsHelper.bindingsBlock(bundleMetadata);
        if (!bindings.isEmpty()) {
            root.put("context_bindings", bindings);
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        return file;
    }

    private List<Map<String, Object>> buildListBlocks(String tenantId) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (AccessListMeta meta : listMetaRepo.listMeta(tenantId)) {
            RuleBinding binding = findListMatchBinding(tenantId, meta.listName());
            if (binding == null) {
                continue;
            }
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("list_name", meta.listName());
            block.put("dimension", meta.dimension());
            if (meta.remark() != null && !meta.remark().isBlank()) {
                block.put("remark", meta.remark());
            }
            block.put("entries", AccessListService.entryMaps(listMetaRepo.listEntries(tenantId, meta.listName())));
            block.put("rule_id", binding.ruleId());
            block.put("rule_revision", binding.ruleRevision());
            block.put("reason_code", binding.reasonCode());
            block.put("risk_score", binding.riskScore());
            block.put("intent_action", binding.intentAction() != null ? binding.intentAction() : "deny");
            block.put("enforce_mode", binding.enforceMode() != null ? binding.enforceMode() : "dry_run");
            if (binding.canaryPercent() != null) {
                block.put("canary_percent", binding.canaryPercent());
            }
            if (binding.valueSource() != null) {
                block.put("value_source", valueSourceMap(binding.valueSource()));
            }
            blocks.add(block);
        }
        blocks.sort((a, b) -> String.valueOf(a.get("list_name")).compareTo(String.valueOf(b.get("list_name"))));
        return blocks;
    }

    private List<Map<String, Object>> buildCumulativeRuleBlocks(String tenantId) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (RuleRevision rule : registryRepo.listCurrentRules(tenantId, "gateway")) {
            if (!RolloutStateHelper.inExecutionPlane(rule) || !"cumulative".equals(rule.runtime())) {
                continue;
            }
            RuleBodyRefs refs = RuleBodyRefs.parse(rule.body());
            if (refs.cumulativeName() == null) {
                continue;
            }
            RuleCondition condition;
            try {
                condition = refs.requireCondition();
            } catch (IllegalArgumentException e) {
                continue;
            }
            cumulativeRepo.get(tenantId, refs.cumulativeName()).ifPresent(def -> {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("cumulative_name", def.cumulativeName());
                block.put("dimension", def.dimension());
                block.put("window_kind", def.windowKind());
                block.put("window_minutes", def.windowMinutes());
                block.put("window_hours", def.windowHours());
                block.put("timezone", def.timezone());
                block.put("threshold", condition.threshold());
                block.put("compare_op", condition.compareOp());
                block.put("rule_id", rule.ruleId());
                block.put("rule_revision", rule.ruleRevision());
                block.put("reason_code", rule.reasonCode());
                block.put("risk_score", rule.riskScore());
                block.put("intent_action", rule.intentAction() != null ? rule.intentAction() : "deny");
                block.put("enforce_mode", rule.enforceMode());
                block.put("canary_percent", rule.exportedCanaryPercent());
                if (rule.canaryPercent() != null) {
                    block.put("canary_percent", rule.canaryPercent());
                }
                if (refs.valueSource() != null) {
                    block.put("value_source", valueSourceMap(refs.valueSource()));
                }
                blocks.add(block);
            });
        }
        blocks.sort((a, b) -> Integer.compare((int) b.get("risk_score"), (int) a.get("risk_score")));
        return blocks;
    }

    private record RuleBinding(
            String ruleId,
            int ruleRevision,
            String reasonCode,
            int riskScore,
            String intentAction,
            String enforceMode,
            Integer canaryPercent,
            io.virbius.policy.ValueSource valueSource) {}

    private RuleBinding findListMatchBinding(String tenantId, String listName) {
        RuleBinding best = null;
        for (RuleRevision rule : registryRepo.listCurrentRules(tenantId, "gateway")) {
            if (!RolloutStateHelper.inExecutionPlane(rule) || !"list_match".equals(rule.runtime())) {
                continue;
            }
            RuleBodyRefs refs = RuleBodyRefs.parse(rule.body());
            if (!listName.equals(refs.listName())) {
                continue;
            }
            RuleBinding candidate = new RuleBinding(
                    rule.ruleId(),
                    rule.ruleRevision(),
                    rule.reasonCode(),
                    rule.riskScore(),
                    rule.intentAction(),
                    rule.enforceMode(),
                    rule.canaryPercent(),
                    refs.valueSource());
            if (best == null || candidate.riskScore() > best.riskScore()) {
                best = candidate;
            }
        }
        return best;
    }

    private Map<String, Object> valueSourceMap(io.virbius.policy.ValueSource vs) {
        Map<String, Object> m = new LinkedHashMap<>();
        String kind =
                switch (vs.kind()) {
                    case REQUEST_FIELD -> "request_field";
                    case VAR -> "var";
                    case HEADER -> "header";
                    case QUERY -> "query";
                    case CONTENT -> "content";
                    case LITERAL -> "literal";
                    default -> "default";
                };
        m.put("kind", kind);
        if (vs.ref() != null) {
            m.put("ref", vs.ref());
        }
        if (vs.literalValue() != null) {
            m.put("value", vs.literalValue());
        }
        return m;
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

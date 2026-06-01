package io.virbius.control.service;

import io.virbius.control.domain.AccessListEntry;
import io.virbius.control.domain.AccessListMeta;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.RuleStatusHelper;
import io.virbius.control.policy.RuleBodyRefs;
import io.virbius.control.repository.CumulativeRepository;
import io.virbius.control.repository.ListMetaRepository;
import io.virbius.control.repository.RegistryRepository;
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

    public ArtifactService(
            @Value("${virbius.data-dir:./data}") String dataDir,
            RegistryRepository registryRepo,
            ListMetaRepository listMetaRepo,
            CumulativeRepository cumulativeRepo) {
        this.dataDir = java.nio.file.Path.of(dataDir);
        this.registryRepo = registryRepo;
        this.listMetaRepo = listMetaRepo;
        this.cumulativeRepo = cumulativeRepo;
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
            if (!RuleStatusHelper.isActive(rule) || !"cumulative".equals(rule.runtime())) {
                continue;
            }
            RuleBodyRefs refs = RuleBodyRefs.parse(rule.body());
            if (refs.cumulativeName() == null) {
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
                block.put("threshold", def.threshold());
                block.put("compare_op", def.compareOp());
                block.put("rule_id", rule.ruleId());
                block.put("rule_revision", rule.ruleRevision());
                block.put("reason_code", rule.reasonCode());
                block.put("risk_score", rule.riskScore());
                block.put("intent_action", rule.intentAction() != null ? rule.intentAction() : "deny");
                block.put("enforce_mode", rule.enforceMode() != null ? rule.enforceMode() : "dry_run");
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
            if (!RuleStatusHelper.isActive(rule) || !"list_match".equals(rule.runtime())) {
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
        java.nio.file.Path file = dir.resolve(tenantId + "-content-lists.json");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tenant_id", tenantId);
        root.put("lists", buildListBlocks(tenantId));
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        return file;
    }
}

package io.virbius.control.service;

import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.RuleStatusHelper;
import io.virbius.control.domain.enums.AccessListDimension;
import io.virbius.control.domain.enums.AccessListPolarity;
import io.virbius.control.repository.AccessListRepository;
import io.virbius.control.repository.RegistryRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public ArtifactService(
            @Value("${virbius.data-dir:./data}") String dataDir, RegistryRepository registryRepo) {
        this.dataDir = java.nio.file.Path.of(dataDir);
        this.registryRepo = registryRepo;
    }

    public Map<String, String> write(String tenantId, AccessListRepository store) {
        return write(tenantId, store, Map.of());
    }

    public Map<String, String> write(String tenantId, AccessListRepository store, Map<String, Object> bundleMetadata) {
        Map<String, String> paths = new LinkedHashMap<>();
        try {
            paths.put("gateway", writeGateway(tenantId, store, bundleMetadata).toString());
            paths.put("edge", writeEdge(tenantId, store).toString());
        } catch (Exception e) {
            log.warn("failed to write list artifacts: {}", e.getMessage());
            paths.put("error", e.getMessage());
        }
        return paths;
    }

    private java.nio.file.Path writeGateway(String tenantId, AccessListRepository store, Map<String, Object> bundleMetadata)
            throws Exception {
        java.nio.file.Path dir = dataDir.resolve("gateway");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Path file = dir.resolve(tenantId + "-access-lists.json");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tenant_id", tenantId);
        root.put("deny", side(store, tenantId, AccessListPolarity.DENY, true));
        root.put("allow", side(store, tenantId, AccessListPolarity.ALLOW, true));
        Map<String, Object> bindings = ContextBindingsHelper.bindingsBlock(bundleMetadata);
        if (!bindings.isEmpty()) {
            root.put("context_bindings", bindings);
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        return file;
    }

    private java.nio.file.Path writeEdge(String tenantId, AccessListRepository store) throws Exception {
        java.nio.file.Path dir = dataDir.resolve("edge");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Path file = dir.resolve(tenantId + "-content-lists.json");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tenant_id", tenantId);
        Map<String, Object> deny = new LinkedHashMap<>();
        deny.put(
                "keywords",
                isRuleActive(tenantId, AccessListRules.EDGE_CONTENT_DENY)
                        ? store.list(tenantId, AccessListPolarity.DENY, AccessListDimension.KEYWORD)
                        : List.of());
        Map<String, Object> allow = new LinkedHashMap<>();
        allow.put(
                "keywords",
                isRuleActive(tenantId, AccessListRules.EDGE_CONTENT_ALLOW)
                        ? store.list(tenantId, AccessListPolarity.ALLOW, AccessListDimension.KEYWORD)
                        : List.of());
        root.put("deny", deny);
        root.put("allow", allow);
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        return file;
    }

    private Map<String, Object> side(
            AccessListRepository store, String tenantId, AccessListPolarity polarity, boolean gateway) {
        Map<String, Object> side = new LinkedHashMap<>();
        if (gateway) {
            String subjectRule = polarity == AccessListPolarity.DENY
                    ? AccessListRules.GW_SUBJECT_DENY
                    : AccessListRules.GW_SUBJECT_ALLOW;
            String requestRule = polarity == AccessListPolarity.DENY
                    ? AccessListRules.GW_REQUEST_DENY
                    : AccessListRules.GW_REQUEST_ALLOW;
            side.put(
                    "user_ids",
                    isRuleActive(tenantId, subjectRule)
                            ? store.list(tenantId, polarity, AccessListDimension.USER_ID)
                            : List.of());
            side.put(
                    "device_ids",
                    isRuleActive(tenantId, subjectRule)
                            ? store.list(tenantId, polarity, AccessListDimension.DEVICE_ID)
                            : List.of());
            side.put(
                    "ip_cidrs",
                    isRuleActive(tenantId, subjectRule)
                            ? store.list(tenantId, polarity, AccessListDimension.IP_CIDR)
                            : List.of());
            side.put(
                    "vars",
                    isRuleActive(tenantId, requestRule)
                            ? store.list(tenantId, polarity, AccessListDimension.VAR)
                            : List.of());
        }
        String contentRule = polarity == AccessListPolarity.DENY
                ? AccessListRules.GW_CONTENT_DENY
                : AccessListRules.GW_CONTENT_ALLOW;
        side.put(
                "keywords",
                isRuleActive(tenantId, contentRule)
                        ? store.list(tenantId, polarity, AccessListDimension.KEYWORD)
                        : List.of());
        return side;
    }

    private boolean isRuleActive(String tenantId, String ruleId) {
        Optional<RuleRevision> rule = registryRepo.getCurrentRule(tenantId, ruleId);
        return rule.map(RuleStatusHelper::isActive).orElse(true);
    }
}

package io.virbius.control.api;

import io.virbius.control.domain.dto.request.AccessListEntriesRequest;
import io.virbius.control.domain.dto.request.UpdateRuleStatusRequest;
import io.virbius.control.domain.dto.request.UpdateRuntimeRequest;
import io.virbius.control.domain.dto.request.UpsertRuleRequest;
import io.virbius.control.domain.enums.AccessListDimension;
import io.virbius.control.domain.enums.AccessListPolarity;
import io.virbius.control.service.AccessListService;
import io.virbius.control.service.BundleMetadataService;
import io.virbius.control.service.BundleService;
import io.virbius.control.service.PublishService;
import io.virbius.control.service.RuleService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 兼容旧脚本路径 {@code /api/v1/tenants/...}；运营台请使用 {@code /api/v1/admin/...} 与 {@code /ui}。
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
public class LegacyApiController {

    private final AccessListService accessListService;
    private final RuleService ruleService;
    private final BundleService bundleService;
    private final PublishService publishService;
    private final BundleMetadataService metadataService;

    public LegacyApiController(
            AccessListService accessListService,
            RuleService ruleService,
            BundleService bundleService,
            PublishService publishService,
            BundleMetadataService metadataService) {
        this.accessListService = accessListService;
        this.ruleService = ruleService;
        this.bundleService = bundleService;
        this.publishService = publishService;
        this.metadataService = metadataService;
    }

    @GetMapping("/access-lists")
    public Map<String, Object> accessListsAll(@PathVariable String tenantId) {
        return accessListService.getAll(tenantId);
    }

    @GetMapping("/access-lists/{dimension}/{polarity}")
    public Map<String, Object> accessListsOne(
            @PathVariable String tenantId, @PathVariable String dimension, @PathVariable String polarity) {
        return Map.of(
                "tenant_id", tenantId,
                "dimension", dimension,
                "polarity", polarity,
                "values",
                accessListService.get(
                        tenantId, AccessListPolarity.parse(polarity), AccessListDimension.parse(dimension)));
    }

    @PostMapping("/access-lists/{dimension}/{polarity}/entries")
    public Map<String, Object> accessListsAdd(
            @PathVariable String tenantId,
            @PathVariable String dimension,
            @PathVariable String polarity,
            @RequestBody AccessListEntriesRequest body) {
        return accessListService.addEntriesAndPush(
                tenantId,
                AccessListPolarity.parse(polarity),
                AccessListDimension.parse(dimension),
                resolveValues(body));
    }

    @DeleteMapping("/access-lists/{dimension}/{polarity}/entries/{value}")
    public Map<String, Object> accessListsRemove(
            @PathVariable String tenantId,
            @PathVariable String dimension,
            @PathVariable String polarity,
            @PathVariable String value) {
        return accessListService.removeEntryAndPush(
                tenantId, AccessListPolarity.parse(polarity), AccessListDimension.parse(dimension), value);
    }

    @PostMapping("/access-lists/sync-rules")
    public Map<String, Object> syncRules(@PathVariable String tenantId) {
        return accessListService.syncRules(tenantId);
    }

    @PostMapping("/access-lists/push-engine")
    public Map<String, Object> pushEngine(@PathVariable String tenantId) {
        return accessListService.pushToEngine(tenantId);
    }

    @PostMapping("/access-lists/sync-and-publish")
    public Map<String, Object> syncAndPublish(
            @PathVariable String tenantId,
            @RequestParam(defaultValue = "poc-default") String bundleId,
            @RequestParam(defaultValue = "0.1.0") String version) {
        return accessListService.syncAndPublish(tenantId, bundleId, version);
    }

    @GetMapping("/bundles")
    public List<Map<String, Object>> listBundles(@PathVariable String tenantId) {
        return bundleService.listBundles(tenantId);
    }

    @GetMapping("/bundles/{bundleId}/versions/{version}")
    public Map<String, Object> getBundleVersion(
            @PathVariable String tenantId, @PathVariable String bundleId, @PathVariable String version) {
        return bundleService.getBundleVersion(tenantId, bundleId, version);
    }

    @PostMapping("/bundles/{bundleId}/versions/{version}/publish")
    public Map<String, Object> publish(
            @PathVariable String tenantId, @PathVariable String bundleId, @PathVariable String version) {
        return publishService.publish(tenantId, bundleId, version);
    }

    @GetMapping("/rules")
    public List<Map<String, Object>> listRules(
            @PathVariable String tenantId, @RequestParam(required = false) String layer) {
        return ruleService.listRules(tenantId, layer);
    }

    @PostMapping("/rules")
    public Map<String, Object> upsertRule(@PathVariable String tenantId, @RequestBody UpsertRuleRequest body) {
        return ruleService.upsertRule(tenantId, body);
    }

    @GetMapping("/rules/{ruleId}")
    public Map<String, Object> getRule(@PathVariable String tenantId, @PathVariable String ruleId) {
        return ruleService.getRule(tenantId, ruleId);
    }

    @PatchMapping("/rules/{ruleId}/status")
    public Map<String, Object> updateRuleStatus(
            @PathVariable String tenantId, @PathVariable String ruleId, @RequestBody UpdateRuleStatusRequest body) {
        return ruleService.updateRuleStatus(tenantId, ruleId, body.ruleStatus());
    }

    @PatchMapping("/rules/{ruleId}/runtime")
    public Map<String, Object> updateRuntime(
            @PathVariable String tenantId, @PathVariable String ruleId, @RequestBody UpdateRuntimeRequest body) {
        return ruleService.updateRuntime(tenantId, ruleId, body.enforceMode(), body.canaryPercent());
    }

    @GetMapping("/bundles/{bundleId}/versions/{version}/metadata")
    public Map<String, Object> getMetadata(
            @PathVariable String tenantId, @PathVariable String bundleId, @PathVariable String version) {
        return metadataService.getMetadata(tenantId, bundleId, version);
    }

    private static List<String> resolveValues(AccessListEntriesRequest body) {
        if (body.values() != null && !body.values().isEmpty()) {
            return body.values();
        }
        if (body.value() != null && !body.value().isBlank()) {
            return List.of(body.value());
        }
        return List.of();
    }
}

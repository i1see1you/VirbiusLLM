package io.virbius.control.api;

import io.virbius.control.domain.dto.request.UpdateRuleStatusRequest;
import io.virbius.control.domain.dto.request.UpdateRuntimeRequest;
import io.virbius.control.domain.dto.request.UpsertRuleRequest;
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

    private final RuleService ruleService;
    private final BundleService bundleService;
    private final PublishService publishService;
    private final BundleMetadataService metadataService;

    public LegacyApiController(
            RuleService ruleService,
            BundleService bundleService,
            PublishService publishService,
            BundleMetadataService metadataService) {
        this.ruleService = ruleService;
        this.bundleService = bundleService;
        this.publishService = publishService;
        this.metadataService = metadataService;
    }

    @GetMapping("/bundles")
    public List<Map<String, Object>> listBundles(@PathVariable("tenantId") String tenantId) {
        return bundleService.listBundles(tenantId);
    }

    @GetMapping("/bundles/{bundleId}/versions/{version}")
    public Map<String, Object> getBundleVersion(
            @PathVariable("tenantId") String tenantId, @PathVariable("bundleId") String bundleId, @PathVariable("version") String version) {
        return bundleService.getBundleVersion(tenantId, bundleId, version);
    }

    @PostMapping("/bundles/{bundleId}/versions/{version}/publish")
    public Map<String, Object> publish(
            @PathVariable("tenantId") String tenantId, @PathVariable("bundleId") String bundleId, @PathVariable("version") String version) {
        return publishService.publish(tenantId, bundleId, version);
    }

    @GetMapping("/rules")
    public List<Map<String, Object>> listRules(
            @PathVariable("tenantId") String tenantId, @RequestParam(value = "layer", required = false) String layer) {
        return ruleService.listRules(tenantId, layer);
    }

    @PostMapping("/rules")
    public Map<String, Object> upsertRule(@PathVariable("tenantId") String tenantId, @RequestBody UpsertRuleRequest body) {
        return ruleService.upsertRule(tenantId, body);
    }

    @GetMapping("/rules/{ruleId}")
    public Map<String, Object> getRule(@PathVariable("tenantId") String tenantId, @PathVariable("ruleId") String ruleId) {
        return ruleService.getRule(tenantId, ruleId);
    }

    @PatchMapping("/rules/{ruleId}/status")
    public Map<String, Object> updateRuleStatus(
            @PathVariable("tenantId") String tenantId, @PathVariable("ruleId") String ruleId, @RequestBody UpdateRuleStatusRequest body) {
        return ruleService.updateRuleStatus(tenantId, ruleId, body.ruleStatus());
    }

    @PatchMapping("/rules/{ruleId}/runtime")
    public Map<String, Object> updateRuntime(
            @PathVariable("tenantId") String tenantId, @PathVariable("ruleId") String ruleId, @RequestBody UpdateRuntimeRequest body) {
        return ruleService.updateRuntime(tenantId, ruleId, body.enforceMode(), body.canaryPercent());
    }

    @GetMapping("/bundles/{bundleId}/versions/{version}/metadata")
    public Map<String, Object> getMetadata(
            @PathVariable("tenantId") String tenantId, @PathVariable("bundleId") String bundleId, @PathVariable("version") String version) {
        return metadataService.getMetadata(tenantId, bundleId, version);
    }
}

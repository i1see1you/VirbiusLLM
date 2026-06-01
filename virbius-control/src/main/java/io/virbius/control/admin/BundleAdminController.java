package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.dto.request.CreateBundleRequest;
import io.virbius.control.domain.dto.request.ContextBindingsRequest;
import io.virbius.control.service.BundleMetadataService;
import io.virbius.control.service.BundleService;
import io.virbius.control.service.PublishService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/bundles")
public class BundleAdminController {

    private final BundleService bundleService;
    private final BundleMetadataService metadataService;
    private final PublishService publishService;

    public BundleAdminController(BundleService bundleService, BundleMetadataService metadataService, PublishService publishService) {
        this.bundleService = bundleService;
        this.metadataService = metadataService;
        this.publishService = publishService;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> listBundles(@PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(bundleService.listBundles(tenantId));
    }

    @PostMapping
    public ApiResult<Map<String, Object>> createBundle(
            @PathVariable("tenantId") String tenantId, @RequestBody CreateBundleRequest body) {
        if (body.bundleId() == null || body.bundleId().isBlank()) {
            throw new IllegalArgumentException("bundle_id required");
        }
        return ApiResult.ok(bundleService.createBundle(tenantId, body.bundleId()));
    }

    @GetMapping("/{bundleId}")
    public ApiResult<Map<String, Object>> getBundle(
            @PathVariable("tenantId") String tenantId, @PathVariable("bundleId") String bundleId) {
        return ApiResult.ok(bundleService.getBundle(tenantId, bundleId));
    }

    @GetMapping("/{bundleId}/versions/{version}")
    public ApiResult<Map<String, Object>> getBundleVersion(
            @PathVariable("tenantId") String tenantId, @PathVariable("bundleId") String bundleId, @PathVariable("version") String version) {
        return ApiResult.ok(bundleService.getBundleVersion(tenantId, bundleId, version));
    }

    @PostMapping("/{bundleId}/versions/{version}/publish")
    public ApiResult<Map<String, Object>> publish(
            @PathVariable("tenantId") String tenantId, @PathVariable("bundleId") String bundleId, @PathVariable("version") String version) {
        return ApiResult.ok(publishService.publish(tenantId, bundleId, version));
    }

    @GetMapping("/{bundleId}/versions/{version}/status")
    public ApiResult<Map<String, Object>> publishStatus(
            @PathVariable("tenantId") String tenantId, @PathVariable("bundleId") String bundleId, @PathVariable("version") String version) {
        return ApiResult.ok(publishService.status(tenantId, bundleId, version));
    }

    @GetMapping("/{bundleId}/versions/{version}/metadata")
    public ApiResult<Map<String, Object>> getMetadata(
            @PathVariable("tenantId") String tenantId, @PathVariable("bundleId") String bundleId, @PathVariable("version") String version) {
        return ApiResult.ok(metadataService.getMetadata(tenantId, bundleId, version));
    }

    @PutMapping("/{bundleId}/versions/{version}/metadata/context-bindings")
    public ApiResult<Map<String, Object>> putContextBindings(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("bundleId") String bundleId,
            @PathVariable("version") String version,
            @RequestParam(value = "sync", defaultValue = "true") boolean sync,
            @RequestBody ContextBindingsRequest body) {
        return ApiResult.ok(metadataService.updateContextBindings(
                tenantId,
                bundleId,
                version,
                BundleMetadataService.parseRequest(body),
                sync));
    }

    @PostMapping("/{bundleId}/versions/{version}/metadata/sync-gateway")
    public ApiResult<Map<String, Object>> syncGateway(@PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(metadataService.syncGatewayArtifacts(tenantId));
    }
}
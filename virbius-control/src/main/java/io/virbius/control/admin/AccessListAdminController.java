package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.dto.request.AccessListEntriesRequest;
import io.virbius.control.domain.enums.AccessListDimension;
import io.virbius.control.domain.enums.AccessListPolarity;
import io.virbius.control.service.AccessListService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/access-lists")
public class AccessListAdminController {

    private final AccessListService accessListService;

    public AccessListAdminController(AccessListService accessListService) {
        this.accessListService = accessListService;
    }

    @GetMapping
    public ApiResult<Map<String, Object>> getAll(@PathVariable String tenantId) {
        return ApiResult.ok(accessListService.getAll(tenantId));
    }

    @GetMapping("/{dimension}/{polarity}")
    public ApiResult<Map<String, Object>> getOne(
            @PathVariable String tenantId, @PathVariable String dimension, @PathVariable String polarity) {
        AccessListDimension dim = AccessListDimension.parse(dimension);
        AccessListPolarity pol = AccessListPolarity.parse(polarity);
        return ApiResult.ok(Map.of(
                "tenant_id", tenantId,
                "dimension", dim.value(),
                "polarity", pol.value(),
                "values", accessListService.get(tenantId, pol, dim)));
    }

    @PutMapping("/{dimension}/{polarity}")
    public ApiResult<Map<String, Object>> replace(
            @PathVariable String tenantId,
            @PathVariable String dimension,
            @PathVariable String polarity,
            @RequestBody AccessListEntriesRequest body) {
        AccessListDimension dim = AccessListDimension.parse(dimension);
        AccessListPolarity pol = AccessListPolarity.parse(polarity);
        List<String> values = body.values() != null ? body.values() : List.of();
        return ApiResult.ok(accessListService.replaceAndPush(tenantId, pol, dim, values));
    }

    @PostMapping("/{dimension}/{polarity}/entries")
    public ApiResult<Map<String, Object>> addEntries(
            @PathVariable String tenantId,
            @PathVariable String dimension,
            @PathVariable String polarity,
            @RequestBody AccessListEntriesRequest body) {
        AccessListDimension dim = AccessListDimension.parse(dimension);
        AccessListPolarity pol = AccessListPolarity.parse(polarity);
        List<String> values = resolveValues(body);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("value or values required");
        }
        return ApiResult.ok(accessListService.addEntriesAndPush(tenantId, pol, dim, values));
    }

    @DeleteMapping("/{dimension}/{polarity}/entries/{value}")
    public ApiResult<Map<String, Object>> removeEntry(
            @PathVariable String tenantId,
            @PathVariable String dimension,
            @PathVariable String polarity,
            @PathVariable String value) {
        AccessListDimension dim = AccessListDimension.parse(dimension);
        AccessListPolarity pol = AccessListPolarity.parse(polarity);
        return ApiResult.ok(accessListService.removeEntryAndPush(tenantId, pol, dim, value));
    }

    @PostMapping("/sync-rules")
    public ApiResult<Map<String, Object>> syncRules(@PathVariable String tenantId) {
        return ApiResult.ok(accessListService.syncRules(tenantId));
    }

    @PostMapping("/push-engine")
    public ApiResult<Map<String, Object>> pushEngine(@PathVariable String tenantId) {
        return ApiResult.ok(accessListService.pushToEngine(tenantId));
    }

    @PostMapping("/sync-and-publish")
    public ApiResult<Map<String, Object>> syncAndPublish(
            @PathVariable String tenantId,
            @RequestParam(defaultValue = "poc-default") String bundleId,
            @RequestParam(defaultValue = "0.1.0") String version) {
        return ApiResult.ok(accessListService.syncAndPublish(tenantId, bundleId, version));
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
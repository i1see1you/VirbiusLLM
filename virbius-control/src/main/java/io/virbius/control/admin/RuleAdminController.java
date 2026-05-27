package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.dto.request.UpdateRuleStatusRequest;
import io.virbius.control.domain.dto.request.UpdateRuntimeRequest;
import io.virbius.control.domain.dto.request.UpsertRuleRequest;
import io.virbius.control.service.PublishService;
import io.virbius.control.service.RuleService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/rules")
public class RuleAdminController {

    private final RuleService ruleService;
    private final PublishService publishService;

    public RuleAdminController(RuleService ruleService, PublishService publishService) {
        this.ruleService = ruleService;
        this.publishService = publishService;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> listRules(
            @PathVariable String tenantId, @RequestParam(required = false) String layer) {
        return ApiResult.ok(ruleService.listRules(tenantId, layer));
    }

    @PostMapping
    public ApiResult<Map<String, Object>> upsertRule(
            @PathVariable String tenantId, @RequestBody UpsertRuleRequest body) {
        return ApiResult.ok(ruleService.upsertRule(tenantId, body));
    }

    @GetMapping("/{ruleId}")
    public ApiResult<Map<String, Object>> getRule(
            @PathVariable String tenantId, @PathVariable String ruleId) {
        return ApiResult.ok(ruleService.getRule(tenantId, ruleId));
    }

    @GetMapping("/{ruleId}/revisions")
    public ApiResult<List<Map<String, Object>>> listRevisions(
            @PathVariable String tenantId, @PathVariable String ruleId) {
        return ApiResult.ok(ruleService.listRevisions(tenantId, ruleId));
    }

    @GetMapping("/{ruleId}/revisions/{revision}")
    public ApiResult<Map<String, Object>> getRevision(
            @PathVariable String tenantId, @PathVariable String ruleId, @PathVariable int revision) {
        return ApiResult.ok(ruleService.getRevision(tenantId, ruleId, revision));
    }

    @PatchMapping("/{ruleId}/status")
    public ApiResult<Map<String, Object>> updateRuleStatus(
            @PathVariable String tenantId,
            @PathVariable String ruleId,
            @RequestBody UpdateRuleStatusRequest body) {
        return ApiResult.ok(ruleService.updateRuleStatus(tenantId, ruleId, body.ruleStatus()));
    }

    @PatchMapping("/{ruleId}/runtime")
    public ApiResult<Map<String, Object>> updateRuntime(
            @PathVariable String tenantId, @PathVariable String ruleId, @RequestBody UpdateRuntimeRequest body) {
        return ApiResult.ok(ruleService.updateRuntime(tenantId, ruleId, body.enforceMode(), body.canaryPercent()));
    }

    /** PoC: {@code ruleId} is ignored; refreshes entire tenant RuleCache (runtime_only). */
    @PostMapping("/{ruleId}/runtime/publish-snapshot")
    public ApiResult<Map<String, Object>> runtimeSnapshot(
            @PathVariable String tenantId, @PathVariable String ruleId) {
        return ApiResult.ok(publishService.runtimeSnapshot(tenantId));
    }
}
package io.virbius.control.admin;

import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.DeployEvent;
import io.virbius.control.domain.DeployRollout;
import io.virbius.control.repository.DeployRolloutRepository;
import io.virbius.control.service.deploy.DeployRolloutService;
import io.virbius.control.service.deploy.NodeRegistryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/deploy-rollout")
public class DeployRolloutController {

    private final DeployRolloutService deployRolloutService;
    private final DeployRolloutRepository rolloutRepo;
    private final NodeRegistryService nodeRegistryService;

    public DeployRolloutController(
            DeployRolloutService deployRolloutService,
            DeployRolloutRepository rolloutRepo,
            NodeRegistryService nodeRegistryService) {
        this.deployRolloutService = deployRolloutService;
        this.rolloutRepo = rolloutRepo;
        this.nodeRegistryService = nodeRegistryService;
    }

    @PostMapping("/prepare")
    public ApiResult<Map<String, Object>> prepare(
            @PathVariable("tenantId") String tenantId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        String bundleId = string(body, "bundle_id");
        String bundleVersion = string(body, "bundle_version");
        String targetVersion = string(body, "target_version");
        String layer = string(body, "layer");
        String description = string(body, "description");
        String operator = resolveOperator(request);

        DeployRollout rollout = deployRolloutService.prepare(
                tenantId, bundleId, bundleVersion, targetVersion, layer, description, operator);
        return ApiResult.ok(toMap(rollout));
    }

    @PostMapping("/{deployId}/upgrade")
    public ApiResult<Map<String, Object>> upgrade(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("deployId") String deployId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String note = body != null ? string(body, "note") : null;
        String operator = resolveOperator(request);
        DeployRollout rollout = deployRolloutService.upgrade(
                tenantId, deployId, operator, note);
        return ApiResult.ok(toMap(rollout));
    }

    @PostMapping("/{deployId}/pause")
    public ApiResult<Map<String, Object>> pause(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("deployId") String deployId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String note = body != null ? string(body, "note") : null;
        String operator = resolveOperator(request);
        DeployRollout rollout = deployRolloutService.pause(
                tenantId, deployId, operator, note);
        return ApiResult.ok(toMap(rollout));
    }

    @PostMapping("/{deployId}/rollback")
    public ApiResult<Map<String, Object>> rollback(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("deployId") String deployId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String note = body != null ? string(body, "note") : null;
        String operator = resolveOperator(request);
        DeployRollout rollout = deployRolloutService.rollback(
                tenantId, deployId, operator, note);
        return ApiResult.ok(toMap(rollout));
    }

    @PostMapping("/{deployId}/deploy-edge")
    public ApiResult<Map<String, Object>> deployEdge(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("deployId") String deployId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String note = body != null ? string(body, "note") : null;
        String operator = resolveOperator(request);
        DeployRollout rollout = deployRolloutService.deployEdge(
                tenantId, deployId, operator, note);
        return ApiResult.ok(toMap(rollout));
    }

    @PostMapping("/{deployId}/finalize")
    public ApiResult<Map<String, Object>> finalize(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("deployId") String deployId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String note = body != null ? string(body, "note") : null;
        String operator = resolveOperator(request);
        DeployRollout rollout = deployRolloutService.finalize(
                tenantId, deployId, operator, note);
        return ApiResult.ok(toMap(rollout));
    }

    @GetMapping("/active")
    public ApiResult<Map<String, Object>> active(
            @PathVariable("tenantId") String tenantId) {
        DeployRollout rollout = rolloutRepo.findActive(tenantId).orElse(null);
        if (rollout == null) {
            return ApiResult.ok(Map.of("active", false));
        }
        Map<String, Object> m = toMap(rollout);
        m.put("events", rolloutRepo.listEvents(rollout.deployId()).stream()
                .map(this::toEventMap).toList());
        m.put("cloud_nodes", nodeRegistryService.listNodes("cloud", tenantId));
        m.put("gateway_nodes", nodeRegistryService.listNodes("gateway", tenantId));
        m.put("pool_distribution", Map.of(
                "cloud", nodeRegistryService.poolDistribution("cloud", tenantId),
                "gateway", nodeRegistryService.poolDistribution("gateway", tenantId)));
        return ApiResult.ok(m);
    }

    @GetMapping("/list")
    public ApiResult<List<Map<String, Object>>> list(
            @PathVariable("tenantId") String tenantId) {
        List<DeployRollout> rollouts = rolloutRepo.listByTenant(tenantId, 20);
        return ApiResult.ok(rollouts.stream().map(this::toMap).toList());
    }

    @GetMapping("/{deployId}")
    public ApiResult<Map<String, Object>> get(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("deployId") String deployId) {
        DeployRollout rollout = rolloutRepo.get(deployId)
                .filter(r -> r.tenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("部署不存在: " + deployId));
        Map<String, Object> m = toMap(rollout);
        m.put("events", rolloutRepo.listEvents(deployId).stream()
                .map(this::toEventMap).toList());
        m.put("cloud_nodes", nodeRegistryService.listNodes("cloud", tenantId));
        m.put("gateway_nodes", nodeRegistryService.listNodes("gateway", tenantId));
        return ApiResult.ok(m);
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private Map<String, Object> toMap(DeployRollout r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deploy_id", r.deployId());
        m.put("tenant_id", r.tenantId());
        m.put("bundle_id", r.bundleId());
        m.put("state", r.state());
        m.put("canary_percent", r.canaryPercent());
        m.put("edge_deployed", r.edgeDeployed());
        m.put("target_version", r.targetVersion());
        m.put("prev_version", r.prevVersion());
        m.put("canary_engine_revision", r.canaryEngineRevision());
        m.put("stable_engine_revision", r.stableEngineRevision());
        m.put("canary_gateway_revision", r.canaryGatewayRevision());
        m.put("stable_gateway_revision", r.stableGatewayRevision());
        m.put("canary_ladder", r.canaryLadder());
        m.put("started_at", r.startedAt());
        m.put("updated_at", r.updatedAt());
        m.put("finalized_at", r.finalizedAt());
        m.put("operator", r.operator());
        m.put("note", r.note());
        return m;
    }

    private Map<String, Object> toEventMap(DeployEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event_id", e.eventId());
        m.put("event_type", e.eventType());
        m.put("reason", e.reason());
        m.put("rule_id", e.ruleId());
        m.put("from_state", e.fromState());
        m.put("from_percent", e.fromPercent());
        m.put("to_state", e.toState());
        m.put("to_percent", e.toPercent());
        m.put("layer", e.layer());
        m.put("operator", e.operator());
        m.put("note", e.note());
        m.put("created_at", e.createdAt());
        return m;
    }

    private static String string(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }

    private static String resolveOperator(HttpServletRequest request) {
        String user = request.getHeader("X-User-Id");
        if (user != null && !user.isBlank()) return user;
        user = request.getRemoteUser();
        if (user != null && !user.isBlank()) return user;
        return "system";
    }
}

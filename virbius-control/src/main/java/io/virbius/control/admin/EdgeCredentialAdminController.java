package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.service.EdgeCredentialService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/edge-credentials")
public class EdgeCredentialAdminController {

    private final EdgeCredentialService credentialService;

    public EdgeCredentialAdminController(EdgeCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@PathVariable String tenantId) {
        return ApiResult.ok(credentialService.list(tenantId));
    }

    @PostMapping
    public ApiResult<Map<String, Object>> issue(@PathVariable String tenantId) {
        return ApiResult.ok(credentialService.issue(tenantId));
    }

    @PostMapping("/{credentialId}/revoke")
    public ApiResult<Map<String, Object>> revoke(
            @PathVariable String tenantId, @PathVariable String credentialId) {
        credentialService.revoke(tenantId, credentialId);
        return ApiResult.ok(Map.of("credential_id", credentialId, "status", "revoked"));
    }
}

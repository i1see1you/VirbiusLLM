package io.virbius.engine.admin;

import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class EngineAdminController {

    private final RuleCache cache;

    public EngineAdminController(RuleCache cache) {
        this.cache = cache;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/policy-version")
    public Map<String, Object> policyVersion(@RequestParam(defaultValue = "default") String tenant_id) {
        return Map.of(
                "tenant_id", tenant_id,
                "policy_version", cache.policyVersion(),
                "cache_generation", cache.cacheGeneration(),
                "loaded_at", cache.loadedAt().toString(),
                "rule_count", cache.ruleCount());
    }

    @PostMapping("/cache/reload")
    public Map<String, Object> reload(
            @RequestParam String tenant_id,
            @RequestParam(required = false) String bundle_version,
            @RequestParam(defaultValue = "full") String mode,
            @RequestBody(required = false) CacheReloadRequest body) {
        long start = System.currentTimeMillis();
        String version = bundle_version != null ? bundle_version : cache.policyVersion();
        List<RuleEntry> rules = body != null && body.rules() != null ? body.rules() : List.of();
        if (!rules.isEmpty()) {
            cache.replaceAll(version, rules);
        }
        return Map.of(
                "ok", true,
                "cache_generation", cache.cacheGeneration(),
                "policy_version", cache.policyVersion(),
                "rules_loaded", cache.ruleCount(),
                "duration_ms", System.currentTimeMillis() - start,
                "mode", mode,
                "tenant_id", tenant_id);
    }
}

package io.virbius.engine.eval;

import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import org.springframework.stereotype.Component;

@Component
public class PolicyDecider {

    private final RuleCache cache;

    public PolicyDecider(RuleCache cache) {
        this.cache = cache;
    }

    public EngineDecisionDto decide(
            String tenantId,
            String sessionId,
            List<SignalDto> signals,
            String primaryRuleId) {
        boolean wouldHit = signals.stream().anyMatch(s -> RiskScore.isBlock((int) s.score())
                || "block".equalsIgnoreCase(s.suggest()));
        RuleEntry l3 = cache.get(tenantId, primaryRuleId != null ? primaryRuleId : "cloud_groovy_l3");
        if (l3 == null) {
            l3 = cache.rulesForTenant(tenantId).stream()
                    .filter(r -> "groovy".equals(r.runtime()))
                    .findFirst()
                    .orElse(null);
        }
        String mode = l3 != null ? l3.enforceMode() : "full";
        int canaryPercent = l3 != null ? l3.canaryPercent() : 100;

        if (!wouldHit) {
            return new EngineDecisionDto("allow", false, null, mode);
        }
        if ("dry_run".equals(mode)) {
            return new EngineDecisionDto("allow", true, null, mode);
        }
        if ("canary".equals(mode) && !inCanaryBucket(sessionId, canaryPercent)) {
            return new EngineDecisionDto("allow", true, null, mode);
        }
        return new EngineDecisionDto("block", false, null, mode);
    }

    private boolean inCanaryBucket(String sessionId, int percent) {
        if (percent >= 100) {
            return true;
        }
        if (percent <= 0) {
            return false;
        }
        String key = sessionId == null || sessionId.isBlank() ? "default" : sessionId;
        CRC32 crc = new CRC32();
        crc.update(key.getBytes(StandardCharsets.UTF_8));
        long bucket = crc.getValue() % 100;
        return bucket < percent;
    }
}

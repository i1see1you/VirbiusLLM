package io.virbius.engine.eval;

import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.groovy.l3.GroovyL3Decision;
import io.virbius.groovy.l3.GroovyL3Executor;
import io.virbius.groovy.l3.L3RuleView;
import io.virbius.groovy.l3.L3SignalView;
import io.virbius.groovy.l3.PolicyContext;
import io.virbius.policy.ActionMerge;
import io.virbius.policy.IntentAction;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GroovyPolicyRunner {

    private static final Logger log = LoggerFactory.getLogger(GroovyPolicyRunner.class);

    private final RuleCache cache;
    private final GroovyL3Executor executor;
    private final PolicyDecider fallback;

    public GroovyPolicyRunner(RuleCache cache, PolicyDecider fallback) {
        this.cache = cache;
        this.executor = new GroovyL3Executor();
        this.fallback = fallback;
    }

    public GroovyPolicyResult decide(
            String tenantId,
            String sessionId,
            String scene,
            Map<String, String> vars,
            List<SignalDto> signals,
            String l3RuleId) {
        String ruleId = l3RuleId != null && !l3RuleId.isBlank() ? l3RuleId : "cloud_groovy_l3";
        RuleEntry l3 = cache.get(tenantId, ruleId);
        if (l3 == null) {
            l3 = cache.rulesForTenant(tenantId).stream()
                    .filter(r -> "groovy".equals(r.runtime()))
                    .findFirst()
                    .orElse(null);
        }
        if (l3 == null) {
            EngineDecisionDto fb = fallback.decide(tenantId, sessionId, signals, ruleId);
            return new GroovyPolicyResult(fb, true, "no_groovy_rule");
        }
        try {
            PolicyContext ctx = buildContext(tenantId, sessionId, scene, ruleId, vars, signals);
            String body = l3.body() != null ? l3.body() : "";
            GroovyL3Decision raw = executor.execute(body, ctx);
            return new GroovyPolicyResult(mapGroovyDecision(raw, sessionId, l3), false, null);
        } catch (Exception e) {
            log.warn("groovy L3 failed tenant={} rule={}: {}", tenantId, ruleId, e.getMessage());
            EngineDecisionDto fb = fallback.decide(tenantId, sessionId, signals, ruleId);
            return new GroovyPolicyResult(fb, true, e.getMessage());
        }
    }

    private EngineDecisionDto mapGroovyDecision(GroovyL3Decision raw, String sessionId, RuleEntry l3) {
        String mode = raw.enforceMode() != null ? raw.enforceMode() : l3.enforceMode();
        if (mode == null || mode.isBlank()) {
            mode = "dry_run";
        }
        String action = raw.effectiveAction() != null ? raw.effectiveAction().toLowerCase(Locale.ROOT) : "allow";
        int canaryPct = l3.canaryPercent() > 0 ? l3.canaryPercent() : 0;
        boolean effective = isEffective(mode, sessionId, canaryPct);
        String resolved =
                switch (action) {
                    case "block", "deny" -> effective ? "block" : "review";
                    case "captcha" -> effective ? "captcha" : "review";
                    case "review" -> "review";
                    default -> "allow";
                };
        return new EngineDecisionDto(resolved, 0, mode);
    }

    private static boolean isEffective(String mode, String sessionId, int canaryPercent) {
        if ("full".equalsIgnoreCase(mode)) {
            return true;
        }
        if ("canary".equalsIgnoreCase(mode)) {
            return ActionMerge.inCanaryBucket(sessionId, canaryPercent);
        }
        return false;
    }

    private PolicyContext buildContext(
            String tenantId,
            String sessionId,
            String scene,
            String currentRuleId,
            Map<String, String> vars,
            List<SignalDto> signals) {
        Map<String, L3RuleView> rules = new HashMap<>();
        for (RuleEntry e : cache.rulesForTenant(tenantId)) {
            if (!"groovy".equals(e.runtime())) {
                continue;
            }
            rules.put(
                    e.ruleId(),
                    new L3RuleView(
                            e.ruleId(),
                            e.ruleRevision(),
                            e.enforceMode() != null ? e.enforceMode() : "full",
                            e.canaryPercent(),
                            e.riskScore()));
        }
        List<L3SignalView> l3Signals = signals.stream()
                .map(s -> new L3SignalView(
                        s.ruleId(),
                        s.ruleRevision(),
                        s.layer(),
                        s.score(),
                        toSuggest(s.intentAction(), (int) s.score()),
                        s.reasonCode()))
                .toList();
        return new PolicyContext(
                tenantId,
                sessionId,
                scene,
                currentRuleId,
                rules,
                l3Signals,
                vars != null ? vars : Map.of());
    }

    private static String toSuggest(String intent, int score) {
        if (intent != null && !intent.isBlank()) {
            if (IntentAction.DENY.equals(intent)) {
                return "block";
            }
            return intent;
        }
        return RiskScore.suggest(score);
    }

    public record GroovyPolicyResult(EngineDecisionDto decision, boolean degraded, String error) {}
}

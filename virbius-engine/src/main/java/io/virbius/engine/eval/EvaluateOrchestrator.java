package io.virbius.engine.eval;

import io.virbius.engine.audit.AuditWriter;
import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.policy.IntentAction;
import io.virbius.policy.MatchContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class EvaluateOrchestrator {

    private final ListMatchRunner listMatchRunner;
    private final PromptRunner promptRunner;
    private final GroovyPolicyRunner groovyPolicyRunner;
    private final CumulativeRunner cumulativeRunner;
    private final AuditWriter auditWriter;
    private final RuleCache ruleCache;
    private final PolicyMerger policyMerger;

    public EvaluateOrchestrator(
            ListMatchRunner listMatchRunner,
            PromptRunner promptRunner,
            GroovyPolicyRunner groovyPolicyRunner,
            CumulativeRunner cumulativeRunner,
            AuditWriter auditWriter,
            RuleCache ruleCache,
            PolicyMerger policyMerger) {
        this.listMatchRunner = listMatchRunner;
        this.promptRunner = promptRunner;
        this.groovyPolicyRunner = groovyPolicyRunner;
        this.cumulativeRunner = cumulativeRunner;
        this.auditWriter = auditWriter;
        this.ruleCache = ruleCache;
        this.policyMerger = policyMerger;
    }

    public EvaluateResponseDto evaluate(EvaluateRequestDto req) {
        Map<String, String> vars = req.vars() != null ? req.vars() : Map.of();
        List<SignalDto> signals = new ArrayList<>();
        if (req.priorSignals() != null) {
            signals.addAll(req.priorSignals());
        }

        MatchContext matchCtx = MatchContext.of(
                req.content(),
                req.userId(),
                req.deviceId(),
                null,
                req.sessionId(),
                vars);
        if (!listMatchRunner.isWhitelisted(req.tenantId(), matchCtx)) {
            signals.addAll(listMatchRunner.run(req.tenantId(), matchCtx));
        }
        signals.addAll(promptRunner.run(req.tenantId(), req.content()));
        signals.addAll(cumulativeRunner.run(req.tenantId(), matchCtx));

        PolicyMerger.PolicyMergeResult merged = policyMerger.merge(req.tenantId(), req.sessionId(), signals);
        EngineDecisionDto decision;
        SignalDto primary;
        boolean degraded;
        if (merged.primarySignal() != null) {
            decision = merged.decision();
            primary = merged.primarySignal();
            degraded = false;
        } else {
            GroovyPolicyRunner.GroovyPolicyResult groovyResult = groovyPolicyRunner.decide(
                    req.tenantId(),
                    req.sessionId(),
                    req.scene(),
                    vars,
                    signals,
                    firstGroovyRuleId(req.tenantId()));
            decision = groovyResult.decision();
            primary = null;
            degraded = groovyResult.degraded();
        }

        String primaryRuleId = primary != null ? primary.ruleId() : firstGroovyRuleId(req.tenantId());
        int primaryRevision = primary != null ? primary.ruleRevision() : 1;
        String reasonCode = primary != null ? primary.reasonCode() : "POLICY_FINAL";

        auditWriter.write(req, decision, primaryRuleId, primaryRevision, reasonCode, degraded);

        return new EvaluateResponseDto(
                decision.effectiveAction(),
                decision.maxRiskScore(),
                primaryRuleId,
                primaryRevision,
                reasonCode,
                req.traceId(),
                degraded);
    }

    private String firstGroovyRuleId(String tenantId) {
        return ruleCache.rulesForTenant(tenantId).stream()
                .filter(r -> "groovy".equals(r.runtime()))
                .map(RuleEntry::ruleId)
                .findFirst()
                .orElse("cloud_groovy_l3");
    }
}

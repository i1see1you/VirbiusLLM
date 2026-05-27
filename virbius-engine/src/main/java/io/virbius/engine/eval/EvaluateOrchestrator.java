package io.virbius.engine.eval;

import io.virbius.engine.audit.AuditWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class EvaluateOrchestrator {

    private final KeywordRunner keywordRunner;
    private final RequestParamRunner requestParamRunner;
    private final PromptRunner promptRunner;
    private final GroovyPolicyRunner groovyPolicyRunner;
    private final AuditWriter auditWriter;

    public EvaluateOrchestrator(
            KeywordRunner keywordRunner,
            RequestParamRunner requestParamRunner,
            PromptRunner promptRunner,
            GroovyPolicyRunner groovyPolicyRunner,
            AuditWriter auditWriter) {
        this.keywordRunner = keywordRunner;
        this.requestParamRunner = requestParamRunner;
        this.promptRunner = promptRunner;
        this.groovyPolicyRunner = groovyPolicyRunner;
        this.auditWriter = auditWriter;
    }

    public EvaluateResponseDto evaluate(EvaluateRequestDto req) {
        Map<String, String> vars = req.vars() != null ? req.vars() : Map.of();
        List<SignalDto> signals = new ArrayList<>();
        if (req.priorSignals() != null) {
            signals.addAll(req.priorSignals());
        }
        if (!requestParamRunner.isWhitelisted(req.tenantId(), vars)) {
            signals.addAll(requestParamRunner.run(req.tenantId(), vars));
        }
        if (!keywordRunner.isWhitelisted(req.tenantId(), req.content())) {
            signals.addAll(keywordRunner.run(req.tenantId(), req.content()));
        }
        signals.addAll(promptRunner.run(req.tenantId(), req.content()));

        String primaryRuleId = "cloud_l1_blacklist";
        int primaryRevision = 1;
        String reasonCode = "EDGE_KEYWORD_BLACKLIST";
        for (SignalDto s : signals) {
            if ("block".equalsIgnoreCase(s.suggest())) {
                primaryRuleId = s.ruleId();
                primaryRevision = s.ruleRevision();
                reasonCode = s.reasonCode();
                break;
            }
        }

        GroovyPolicyRunner.GroovyPolicyResult groovyResult = groovyPolicyRunner.decide(
                req.tenantId(), req.sessionId(), req.scene(), vars, signals, "cloud_groovy_l3");
        EngineDecisionDto decision = groovyResult.decision();
        auditWriter.write(req, decision, primaryRuleId, primaryRevision, reasonCode);

        return new EvaluateResponseDto(
                signals,
                decision,
                primaryRuleId,
                primaryRevision,
                reasonCode,
                req.traceId(),
                groovyResult.degraded());
    }
}

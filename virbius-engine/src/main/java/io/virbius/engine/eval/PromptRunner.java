package io.virbius.engine.eval;

import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.engine.config.PromptLlmProperties;
import io.virbius.engine.eval.PromptAuditJsonParser.PromptAuditResult;
import io.virbius.policy.MatchContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PromptRunner {

    private static final Logger log = LoggerFactory.getLogger(PromptRunner.class);

    private final RuleCache cache;
    private final PromptLlmProperties llmProps;
    private final PromptLlmClient llmClient;
    private final PromptAuditJsonParser auditParser;

    public PromptRunner(
            RuleCache cache,
            PromptLlmProperties llmProps,
            PromptLlmClient llmClient,
            PromptAuditJsonParser auditParser) {
        this.cache = cache;
        this.llmProps = llmProps;
        this.llmClient = llmClient;
        this.auditParser = auditParser;
    }

    public List<SignalDto> run(String tenantId, MatchContext matchCtx) {
        if (matchCtx == null || matchCtx.content() == null || matchCtx.content().isBlank()) {
            return List.of();
        }
        List<RuleEntry> promptRules = promptRulesBound(tenantId, matchCtx);
        if (promptRules.isEmpty()) {
            return List.of();
        }
        return runMatrixLlm(matchCtx.content(), promptRules);
    }

    private List<SignalDto> runMatrixLlm(String content, List<RuleEntry> promptRules) {
        String prompt = PromptMatrixBuilder.buildChatMlPrompt(llmProps, promptRules, content);
        String assistant = llmClient.complete(prompt);
        if (assistant == null || assistant.isBlank()) {
            if (llmProps.failOpen()) {
                log.warn("prompt-llm empty response; fail-open");
                return List.of();
            }
            return fallbackOnLlmError(content, promptRules, "LLM unavailable");
        }
        PromptAuditResult audit = auditParser.parse(assistant);
        if (!audit.hitRule()) {
            return List.of();
        }
        RuleEntry matched = resolveRule(promptRules, audit.triggeredId());
        if (matched == null) {
            matched = promptRules.get(0);
            log.warn("prompt-llm hit but triggered_id {} unknown; using {}", audit.triggeredId(), matched.ruleId());
        }
        return List.of(toSignal(matched, audit.reason()));
    }

    private List<SignalDto> fallbackOnLlmError(String content, List<RuleEntry> promptRules, String reason) {
        log.warn("prompt-llm fail-closed: {}", reason);
        RuleEntry top = promptRules.get(0);
        return List.of(toSignal(top, reason));
    }

    private static RuleEntry resolveRule(List<RuleEntry> rules, String triggeredId) {
        if (triggeredId == null || triggeredId.isBlank()) {
            return null;
        }
        for (RuleEntry r : rules) {
            if (triggeredId.equals(r.ruleId())) {
                return r;
            }
        }
        String lower = triggeredId.toLowerCase(Locale.ROOT);
        for (RuleEntry r : rules) {
            if (r.ruleId().equalsIgnoreCase(triggeredId) || r.ruleId().toLowerCase(Locale.ROOT).equals(lower)) {
                return r;
            }
        }
        return null;
    }

    private List<RuleEntry> promptRulesBound(String tenantId, MatchContext matchCtx) {
        List<RuleEntry> out = new ArrayList<>();
        for (RuleEntry rule : cache.rulesForTenant(tenantId)) {
            if (!"prompt".equals(rule.runtime())) {
                continue;
            }
            if (RuleScopeSupport.matchesBind(rule, matchCtx)) {
                out.add(rule);
            }
        }
        return out;
    }

    private static SignalDto toSignal(RuleEntry rule, String auditReason) {
        String reason = rule.reasonCode();
        if (auditReason != null && !auditReason.isBlank()) {
            reason = auditReason;
        }
        return new SignalDto(
                rule.ruleId(),
                rule.ruleRevision(),
                "cloud",
                "cloud",
                rule.riskScore(),
                reason,
                rule.intentAction(),
                rule.enforceMode(),
                rule.canaryPercent() > 0 ? rule.canaryPercent() : null);
    }
}

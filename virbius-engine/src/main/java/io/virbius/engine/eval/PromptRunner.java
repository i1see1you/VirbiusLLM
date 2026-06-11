package io.virbius.engine.eval;

import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.engine.config.PromptLlmProperties;
import io.virbius.engine.eval.PromptAuditJsonParser.PromptAuditResult;
import io.virbius.policy.MatchContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class PromptRunner {

    private static final Logger log = LoggerFactory.getLogger(PromptRunner.class);

    private final RuleCache cache;
    private final PromptLlmProperties llmProps;
    private final PromptLlmClient llmClient;
    private final PromptAuditJsonParser auditParser;
    private final ExecutorService asyncExecutor;
    private final AsyncActionHandler asyncActionHandler;

    public PromptRunner(
            RuleCache cache,
            PromptLlmProperties llmProps,
            PromptLlmClient llmClient,
            PromptAuditJsonParser auditParser,
            @Qualifier("asyncRuleExecutor") ExecutorService asyncExecutor,
            AsyncActionHandler asyncActionHandler) {
        this.cache = cache;
        this.llmProps = llmProps;
        this.llmClient = llmClient;
        this.auditParser = auditParser;
        this.asyncExecutor = asyncExecutor;
        this.asyncActionHandler = asyncActionHandler;
    }

    public List<SignalDto> run(String tenantId, MatchContext matchCtx) {
        if (matchCtx == null || matchCtx.content() == null || matchCtx.content().isBlank()) {
            return List.of();
        }
        List<RuleEntry> allPromptRules = promptRulesBound(tenantId, matchCtx);
        if (allPromptRules.isEmpty()) {
            return List.of();
        }
        List<RuleEntry> syncRules = new ArrayList<>();
        List<RuleEntry> asyncRules = new ArrayList<>();
        for (RuleEntry rule : allPromptRules) {
            if (rule.isAsync()) {
                asyncRules.add(rule);
            } else {
                syncRules.add(rule);
            }
        }
        if (!asyncRules.isEmpty()) {
            asyncExecutor.submit(() -> runPromptAsync(matchCtx, asyncRules));
        }
        if (!syncRules.isEmpty()) {
            return runMatrixLlm(matchCtx.content(), syncRules);
        }
        return List.of();
    }

    private void runPromptAsync(MatchContext matchCtx, List<RuleEntry> promptRules) {
        try {
            List<SignalDto> signals = runMatrixLlm(matchCtx.content(), promptRules);
            for (SignalDto signal : signals) {
                RuleEntry matched = null;
                for (RuleEntry r : promptRules) {
                    if (r.ruleId().equals(signal.ruleId())) {
                        matched = r;
                        break;
                    }
                }
                if (matched != null) {
                    asyncActionHandler.executeIfConfigured(matched, signal, matchCtx);
                    log.info("async prompt rule hit tenant={} rule={} revision={}",
                            matched.tenantId(), matched.ruleId(), matched.ruleRevision());
                }
            }
        } catch (Exception e) {
            log.warn("async prompt rule eval failed: {}", e.getMessage());
        }
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
                rule.canaryPercent() > 0 ? rule.canaryPercent() : null,
                null);
    }
}

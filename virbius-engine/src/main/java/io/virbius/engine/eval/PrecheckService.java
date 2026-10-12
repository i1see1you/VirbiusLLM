package io.virbius.engine.eval;

import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.engine.config.PromptLlmProperties;
import io.virbius.engine.eval.PromptAuditJsonParser.PromptAuditResult;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Offline precheck for the QA publish chain: classifies content against the tenant's prompt rules
 * with a single aggregated LLM call. No audit, no async action, no counter side effects.
 */
@Service
public class PrecheckService {

    private final RuleCache cache;
    private final PromptLlmProperties llmProps;
    private final PromptLlmClient llmClient;
    private final PromptAuditJsonParser auditParser;

    public PrecheckService(
            RuleCache cache,
            PromptLlmProperties llmProps,
            PromptLlmClient llmClient,
            PromptAuditJsonParser auditParser) {
        this.cache = cache;
        this.llmProps = llmProps;
        this.llmClient = llmClient;
        this.auditParser = auditParser;
    }

    public PrecheckResponseDto precheck(PrecheckRequestDto request) {
        String tenantId = request.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }
        String content = request.content();
        if (content == null || content.isBlank()) {
            return new PrecheckResponseDto(false, null, null, null, null, "content required", 0);
        }

        List<RuleEntry> promptRules = cache.rulesForTenant(tenantId).stream()
                .filter(r -> "prompt".equalsIgnoreCase(r.runtime()))
                .toList();
        if (promptRules.isEmpty()) {
            return new PrecheckResponseDto(false, null, null, null, null, null, 0);
        }

        String prompt = PromptMatrixBuilder.buildChatMlPrompt(llmProps, content);
        PromptLlmClient.CompleteResult llm = llmClient.completeDetail(prompt);
        if (llm.error() != null && !llm.error().isBlank()) {
            return new PrecheckResponseDto(
                    false, null, null, null, llm.content(), llm.error(), promptRules.size());
        }
        String assistant = llm.content();
        if (assistant == null || assistant.isBlank()) {
            return new PrecheckResponseDto(
                    false,
                    null,
                    null,
                    null,
                    assistant,
                    "LLM empty response (model=" + llmProps.model() + ")",
                    promptRules.size());
        }

        PromptAuditResult audit = auditParser.parse(assistant);
        String category = audit.category();
        String triggeredRuleId = PromptCategoryUtil.ruleIdFor(llmProps.categoryRuleMapping(), category);
        return new PrecheckResponseDto(
                audit.hitRule(),
                category,
                triggeredRuleId,
                audit.reason(),
                assistant,
                null,
                promptRules.size());
    }
}

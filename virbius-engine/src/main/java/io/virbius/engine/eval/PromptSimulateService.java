package io.virbius.engine.eval;

import io.virbius.engine.cache.RuleEntry;
import io.virbius.engine.config.PromptLlmProperties;
import io.virbius.engine.eval.PromptAuditJsonParser.PromptAuditResult;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** Simulates a single draft prompt rule via one 1B matrix call. */
@Service
public class PromptSimulateService {

    private final PromptLlmProperties llmProps;
    private final PromptLlmClient llmClient;
    private final PromptAuditJsonParser auditParser;

    public PromptSimulateService(
            PromptLlmProperties llmProps, PromptLlmClient llmClient, PromptAuditJsonParser auditParser) {
        this.llmProps = llmProps;
        this.llmClient = llmClient;
        this.auditParser = auditParser;
    }

    public PromptSimulateResponseDto simulate(PromptSimulateRequestDto request) {
        String ruleId = request.ruleId();
        if (ruleId == null || ruleId.isBlank()) {
            return new PromptSimulateResponseDto(false, false, null, null, null, "rule_id required");
        }
        String content = request.content();
        if (content == null || content.isBlank()) {
            return new PromptSimulateResponseDto(false, false, null, null, null, "content required");
        }

        RuleEntry draft = draftRule(ruleId, request.body(), request.reasonCode());
        String prompt = PromptMatrixBuilder.buildChatMlPrompt(llmProps, List.of(draft), content);
        PromptLlmClient.CompleteResult llm = llmClient.completeDetail(prompt);
        if (llm.error() != null && !llm.error().isBlank()) {
            return new PromptSimulateResponseDto(
                    false, false, null, null, llm.content(), llm.error());
        }
        String assistant = llm.content();
        if (assistant == null || assistant.isBlank()) {
            return new PromptSimulateResponseDto(
                    false, false, null, null, assistant, "LLM empty response (model=" + llmProps.model() + ")");
        }

        PromptAuditResult audit = auditParser.parse(assistant);
        boolean hit = audit.hitRule() && ruleIdsMatch(ruleId, audit.triggeredId());
        return new PromptSimulateResponseDto(
                hit, audit.hitRule(), audit.triggeredId(), audit.reason(), assistant, null);
    }

    private static RuleEntry draftRule(String ruleId, String body, String reasonCode) {
        return new RuleEntry(
                "simulate",
                ruleId,
                1,
                "cloud",
                "prompt",
                reasonCode != null ? reasonCode : "SIMULATE",
                100,
                "deny",
                "dry_run",
                0,
                "dry_run",
                body != null ? body : "",
                null,
                false,
                null);
    }

    private static boolean ruleIdsMatch(String expected, String triggeredId) {
        if (triggeredId == null || triggeredId.isBlank()) {
            return false;
        }
        if (expected.equals(triggeredId)) {
            return true;
        }
        return expected.equalsIgnoreCase(triggeredId)
                || expected.toLowerCase(Locale.ROOT).equals(triggeredId.toLowerCase(Locale.ROOT));
    }
}

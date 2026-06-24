package io.virbius.engine.eval;

import io.virbius.engine.cache.RuleEntry;
import io.virbius.engine.config.PromptLlmProperties;
import java.util.List;

/** Builds one aggregated ChatML prompt for all {@code runtime=prompt} rules (single LLM call). */
public final class PromptMatrixBuilder {

    private PromptMatrixBuilder() {}

    public static String buildChatMlPrompt(
            PromptLlmProperties props, List<RuleEntry> rules, String userContent) {
        StringBuilder matrix = new StringBuilder();
        for (RuleEntry rule : rules) {
            matrix.append("- [")
                    .append(rule.ruleId())
                    .append("]: ")
                    .append(ruleBodyText(rule))
                    .append('\n');
        }
        String safeUser = userContent.replace("\\", "\\\\").replace("\"", "\\\"");
        return props.imStart()
                + "system\n"
                + "You are a strict LLM input auditor. Determine if the user input triggers any rule in the following [Security Rule Matrix].\n"
                + "If triggered, you MUST block and identify the exact rule code.\n\n"
                + "[Security Rule Matrix]:\n"
                + matrix
                + "\n"
                + "[Output Format]:\n"
                + "You MUST output strict JSON only, with no extra text. Format:\n"
                + "{\n"
+ "  \"hit_rule\": true or false,\n"
+ "  \"triggered_id\": \"specific Rule_ID if triggered (same as in matrix brackets), otherwise null\",\n"
+ "  \"reason\": \"brief reason for blocking\"\n"
                + "}\n"
                + props.imEnd()
                + "\n"
                + props.imStart()
                + "user\n"
                + "[User Prompt]: \""
                + safeUser
                + "\"\n"
                + props.imEnd()
                + "\n"
                + props.imStart()
                + "assistant\n";
    }

    private static String ruleBodyText(RuleEntry rule) {
        String body = rule.body();
        if (body == null || body.isBlank()) {
            return rule.reasonCode() != null ? rule.reasonCode() : "rule description not configured";
        }
        return body.trim();
    }
}

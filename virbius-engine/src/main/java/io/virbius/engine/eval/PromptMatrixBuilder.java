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
                + "你是一个严格的大模型输入审计员。请同时判定用户的输入是否触发了以下【安全规则矩阵】中的任意一条。\n"
                + "如果触发，必须立刻拦截，并指出具体触发了哪一条规则的代码。\n\n"
                + "【安全规则矩阵】:\n"
                + matrix
                + "\n"
                + "【输出格式要求】:\n"
                + "你必须严格输出 JSON 格式，绝不能有任何多余的废话。格式如下：\n"
                + "{\n"
                + "  \"hit_rule\": true 或 false,\n"
                + "  \"triggered_id\": \"若触发，写明具体 Rule_ID（与矩阵方括号内一致），否则为 null\",\n"
                + "  \"reason\": \"简述拦截原因\"\n"
                + "}\n"
                + props.imEnd()
                + "\n"
                + props.imStart()
                + "user\n"
                + "【User Prompt】: \""
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
            return rule.reasonCode() != null ? rule.reasonCode() : "未配置规则描述";
        }
        return body.trim();
    }
}

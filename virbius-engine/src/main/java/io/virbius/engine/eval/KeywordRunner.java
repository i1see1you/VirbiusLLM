package io.virbius.engine.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 从 RuleCache 规则 {@code body} 中的 {@code keywords} 数组匹配；词表仅来自 Registry 发布，无内置默认词。
 */
@Component
public class KeywordRunner {

    private final RuleCache cache;
    private final ObjectMapper mapper = new ObjectMapper();

    public KeywordRunner(RuleCache cache) {
        this.cache = cache;
    }

    public List<SignalDto> run(String tenantId, String content) {
        List<SignalDto> signals = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return signals;
        }
        String lower = content.toLowerCase();
        for (RuleEntry rule : cache.rulesForTenant(tenantId)) {
            if (!isBlacklistRule(rule)) {
                continue;
            }
            List<String> keywords = keywordsFromBody(rule.body());
            if (keywords.isEmpty()) {
                continue;
            }
            for (String kw : keywords) {
                if (kw.isEmpty()) {
                    continue;
                }
                if (matches(content, lower, kw)) {
                    signals.add(new SignalDto(
                            rule.ruleId(),
                            rule.ruleRevision(),
                            rule.layer(),
                            rule.layer(),
                            rule.riskScore(),
                            RiskScore.suggest(rule.riskScore()),
                            rule.reasonCode()));
                    break;
                }
            }
        }
        return signals;
    }

    /** 命中白名单词时跳过黑名单拦截（白名单优先）。 */
    public boolean isWhitelisted(String tenantId, String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String lower = content.toLowerCase();
        for (RuleEntry rule : cache.rulesForTenant(tenantId)) {
            if (!isWhitelistRule(rule)) {
                continue;
            }
            for (String kw : keywordsFromBody(rule.body())) {
                if (!kw.isEmpty() && matches(content, lower, kw)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isBlacklistRule(RuleEntry rule) {
        if (rule.ruleId() != null && rule.ruleId().contains("whitelist")) {
            return false;
        }
        return !"whitelist".equals(listTypeFromBody(rule.body()));
    }

    private boolean isWhitelistRule(RuleEntry rule) {
        if (rule.ruleId() != null && rule.ruleId().contains("whitelist")) {
            return true;
        }
        return "whitelist".equals(listTypeFromBody(rule.body()));
    }

    private String listTypeFromBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode node = mapper.readTree(body);
            JsonNode lt = node.get("list_type");
            return lt != null ? lt.asText("").trim().toLowerCase() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private List<String> keywordsFromBody(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = mapper.readTree(body);
            JsonNode arr = node.get("keywords");
            if (arr == null || !arr.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            arr.forEach(n -> {
                String text = n.asText();
                if (!text.isBlank()) {
                    out.add(text);
                }
            });
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean matches(String content, String lower, String kw) {
        if (kw.chars().anyMatch(c -> c > 127)) {
            return content.contains(kw);
        }
        return lower.contains(kw.toLowerCase());
    }
}

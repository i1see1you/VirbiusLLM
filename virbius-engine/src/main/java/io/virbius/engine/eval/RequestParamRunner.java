package io.virbius.engine.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 匹配 RuleCache 中 {@code vars} 名单（逻辑变量名=value，管云同步）。 */
@Component
public class RequestParamRunner {

    private final RuleCache cache;
    private final ObjectMapper mapper = new ObjectMapper();

    public RequestParamRunner(RuleCache cache) {
        this.cache = cache;
    }

    public List<SignalDto> run(String tenantId, Map<String, String> vars) {
        List<SignalDto> signals = new ArrayList<>();
        if (vars == null || vars.isEmpty()) {
            return signals;
        }
        for (RuleEntry rule : cache.rulesForTenant(tenantId)) {
            if (!isContextVarRule(rule)) {
                continue;
            }
            if (!"deny".equals(listTypeFromBody(rule.body()))) {
                continue;
            }
            if (matches(rule, vars)) {
                signals.add(new SignalDto(
                        rule.ruleId(),
                        rule.ruleRevision(),
                        rule.layer(),
                        rule.layer(),
                        rule.riskScore(),
                        RiskScore.suggest(rule.riskScore()),
                        rule.reasonCode()));
            }
        }
        return signals;
    }

    public boolean isWhitelisted(String tenantId, Map<String, String> vars) {
        if (vars == null || vars.isEmpty()) {
            return false;
        }
        for (RuleEntry rule : cache.rulesForTenant(tenantId)) {
            if (!isContextVarRule(rule)) {
                continue;
            }
            if (!"allow".equals(listTypeFromBody(rule.body()))) {
                continue;
            }
            if (matches(rule, vars)) {
                return true;
            }
        }
        return false;
    }

    private boolean isContextVarRule(RuleEntry rule) {
        if (rule.body() == null || rule.body().isBlank()) {
            return false;
        }
        try {
            JsonNode node = mapper.readTree(rule.body());
            return node.has("vars") && node.get("vars").isArray();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean matches(RuleEntry rule, Map<String, String> vars) {
        for (String entry : varsFromBody(rule.body())) {
            String[] nv = splitVarEntry(entry);
            if (nv == null) {
                continue;
            }
            String actual = vars.get(nv[0]);
            if (nv[1].equals(actual)) {
                return true;
            }
        }
        return false;
    }

    private String[] splitVarEntry(String entry) {
        int eq = entry.indexOf('=');
        if (eq <= 0 || eq >= entry.length() - 1) {
            return null;
        }
        String logical = entry.substring(0, eq).trim().toLowerCase(Locale.ROOT);
        String value = entry.substring(eq + 1).trim();
        if (logical.isEmpty() || value.isEmpty()) {
            return null;
        }
        return new String[] {logical, value};
    }

    private String listTypeFromBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode node = mapper.readTree(body);
            if (node.has("list_type")) {
                return node.get("list_type").asText("");
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private List<String> varsFromBody(String body) {
        return stringArrayFromBody(body, "vars");
    }

    private List<String> stringArrayFromBody(String body, String field) {
        List<String> out = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        try {
            JsonNode node = mapper.readTree(body);
            JsonNode arr = node.get(field);
            if (arr != null && arr.isArray()) {
                for (JsonNode item : arr) {
                    if (item.isTextual()) {
                        out.add(item.asText());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}

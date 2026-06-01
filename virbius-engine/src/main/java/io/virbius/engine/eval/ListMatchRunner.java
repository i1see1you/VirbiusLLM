package io.virbius.engine.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.policy.IntentAction;
import io.virbius.policy.ListMatcher;
import io.virbius.policy.MatchContext;
import io.virbius.policy.ValueResolver;
import io.virbius.policy.ValueSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Cloud-side {@code list_match} rules (materialized body from control PolicyMaterializer). */
@Component
public class ListMatchRunner {

    private final RuleCache cache;
    private final ObjectMapper mapper = new ObjectMapper();

    public ListMatchRunner(RuleCache cache) {
        this.cache = cache;
    }

    public List<SignalDto> run(String tenantId, MatchContext ctx) {
        List<SignalDto> signals = new ArrayList<>();
        for (RuleEntry rule : cache.rulesForTenant(tenantId)) {
            if (!"list_match".equals(rule.runtime()) || isAllowRule(rule)) {
                continue;
            }
            if (matchesRule(rule, ctx)) {
                signals.add(toSignal(rule));
            }
        }
        return signals;
    }

    public boolean isWhitelisted(String tenantId, MatchContext ctx) {
        for (RuleEntry rule : cache.rulesForTenant(tenantId)) {
            if (!"list_match".equals(rule.runtime()) || !isAllowRule(rule)) {
                continue;
            }
            if (matchesRule(rule, ctx)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesRule(RuleEntry rule, MatchContext ctx) {
        JsonNode body = parseBody(rule.body());
        if (body == null) {
            return false;
        }
        String dimension = text(body, "dimension");
        if (dimension == null || dimension.isBlank()) {
            return false;
        }
        List<String> entries = entriesFromBody(body);
        if (entries.isEmpty()) {
            return false;
        }
        if ("var".equalsIgnoreCase(dimension)) {
            return matchesVarEntries(entries, ctx.varsOrEmpty());
        }
        ValueSource vs = ValueSource.fromJson(body.get("value_source"));
        Optional<String> value = ValueResolver.resolve(dimension, vs, ctx);
        String content = ctx.content() != null ? ctx.content() : "";
        if ("keyword".equalsIgnoreCase(dimension) || "content".equalsIgnoreCase(dimension)) {
            return ListMatcher.match(dimension, content, content, entries);
        }
        if (value.isEmpty()) {
            return false;
        }
        return ListMatcher.match(dimension, value.get(), content, entries);
    }

    private static boolean matchesVarEntries(List<String> entries, Map<String, String> vars) {
        if (vars.isEmpty()) {
            return false;
        }
        for (String entry : entries) {
            String[] nv = splitVarEntry(entry);
            if (nv == null) {
                continue;
            }
            if (nv[1].equals(vars.get(nv[0]))) {
                return true;
            }
        }
        return false;
    }

    private static String[] splitVarEntry(String entry) {
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

    private boolean isAllowRule(RuleEntry rule) {
        if (rule.intentAction() != null && IntentAction.isAllowIntent(rule.intentAction())) {
            return true;
        }
        if (rule.riskScore() <= 0) {
            return true;
        }
        JsonNode body = parseBody(rule.body());
        if (body == null) {
            return false;
        }
        String listType = text(body, "list_type");
        return "allow".equalsIgnoreCase(listType);
    }

    private List<String> entriesFromBody(JsonNode body) {
        List<String> keywords = stringArray(body, "keywords");
        if (!keywords.isEmpty()) {
            return keywords;
        }
        List<String> vars = stringArray(body, "vars");
        if (!vars.isEmpty()) {
            return vars;
        }
        return stringArray(body, "values");
    }

    private SignalDto toSignal(RuleEntry rule) {
        return new SignalDto(
                rule.ruleId(),
                rule.ruleRevision(),
                rule.layer(),
                rule.layer(),
                rule.riskScore(),
                rule.reasonCode(),
                rule.intentAction(),
                rule.enforceMode(),
                rule.canaryPercent() > 0 ? rule.canaryPercent() : null);
    }

    private JsonNode parseBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n != null && !n.isNull() ? n.asText() : null;
    }

    private static List<String> stringArray(JsonNode body, String field) {
        List<String> out = new ArrayList<>();
        JsonNode arr = body.get(field);
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode item : arr) {
            if (item.isTextual() && !item.asText().isBlank()) {
                out.add(item.asText());
            }
        }
        return out;
    }
}

package io.virbius.engine.eval;

import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PromptRunner {

    private static final Pattern INJECTION = Pattern.compile(
            "(?i)(jailbreak|\\bdan\\b|ignore previous|绕过|越狱)");

    private final RuleCache cache;

    public PromptRunner(RuleCache cache) {
        this.cache = cache;
    }

    public List<SignalDto> run(String tenantId, String content) {
        List<SignalDto> signals = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return signals;
        }
        for (RuleEntry rule : cache.rulesForTenant(tenantId)) {
            if (!"prompt".equals(rule.runtime())) {
                continue;
            }
            if (INJECTION.matcher(content).find()) {
                signals.add(new SignalDto(
                        rule.ruleId(),
                        rule.ruleRevision(),
                        "cloud",
                        "cloud",
                        rule.riskScore(),
                        rule.reasonCode(),
                        rule.intentAction(),
                        rule.enforceMode(),
                        rule.canaryPercent() > 0 ? rule.canaryPercent() : null));
            }
        }
        return signals;
    }
}

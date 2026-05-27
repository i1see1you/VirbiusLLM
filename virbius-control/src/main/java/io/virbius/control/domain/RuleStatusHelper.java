package io.virbius.control.domain;

import io.virbius.control.domain.enums.RuleStatus;

public final class RuleStatusHelper {

    private RuleStatusHelper() {}

    public static boolean isActive(RuleRevision rule) {
        if (rule == null || rule.ruleStatus() == null || rule.ruleStatus().isBlank()) {
            return true;
        }
        return RuleStatus.ACTIVE.value().equals(rule.ruleStatus());
    }

    public static void requireActive(RuleRevision rule) {
        if (!isActive(rule)) {
            throw new IllegalStateException("rule is disabled and cannot be modified: " + rule.ruleId());
        }
    }

    public static String normalize(String raw) {
        return RuleStatus.parse(raw).value();
    }
}

package io.virbius.control.domain;

import io.virbius.control.domain.enums.RuleStatus;

public final class RuleStatusHelper {

    private RuleStatusHelper() {}

    public static String statusOf(RuleRevision rule) {
        if (rule == null || rule.ruleStatus() == null || rule.ruleStatus().isBlank()) {
            return RuleStatus.DRAFT.value();
        }
        return rule.ruleStatus();
    }

    public static boolean isActive(RuleRevision rule) {
        return RuleStatus.ACTIVE.value().equals(statusOf(rule));
    }

    public static boolean isEditable(RuleRevision rule) {
        String s = statusOf(rule);
        return RuleStatus.DRAFT.value().equals(s) || RuleStatus.ACTIVE.value().equals(s);
    }

    public static void requireEditable(RuleRevision rule) {
        if (!isEditable(rule)) {
            throw new IllegalStateException("rule is disabled and cannot be modified: " + rule.ruleId());
        }
    }

    public static void requireActive(RuleRevision rule) {
        if (!isActive(rule)) {
            throw new IllegalStateException("rule is not active: " + rule.ruleId());
        }
    }

    public static void validateTransition(String fromStatus, String toStatus) {
        String from = normalize(fromStatus);
        String to = normalize(toStatus);
        if (from.equals(to)) {
            return;
        }
        boolean allowed =
                switch (from) {
                    case "draft" -> "active".equals(to) || "disabled".equals(to);
                    case "active" -> "disabled".equals(to);
                    case "disabled" -> "draft".equals(to);
                    default -> false;
                };
        if (!allowed) {
            throw new IllegalArgumentException("invalid rule_status transition: " + from + " -> " + to);
        }
    }

    public static String normalize(String raw) {
        return RuleStatus.parse(raw).value();
    }
}

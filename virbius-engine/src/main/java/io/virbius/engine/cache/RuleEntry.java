package io.virbius.engine.cache;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RuleEntry(
        String tenantId,
        String ruleId,
        int ruleRevision,
        String layer,
        String runtime,
        String reasonCode,
        @JsonProperty("risk_score") int riskScore,
        @JsonProperty("intent_action") String intentAction,
        @JsonProperty("enforce_mode") String enforceMode,
        @JsonProperty("canary_percent") int canaryPercent,
        @JsonProperty("rollout_state") String rolloutState,
        String body,
        Object scope) {

    public String rolloutStateOrDefault() {
        if (rolloutState != null && !rolloutState.isBlank()) {
            return rolloutState;
        }
        if (enforceMode == null) {
            return "dry_run";
        }
        return switch (enforceMode.toLowerCase()) {
            case "full" -> "full";
            case "canary" -> "canary";
            default -> "dry_run";
        };
    }
}

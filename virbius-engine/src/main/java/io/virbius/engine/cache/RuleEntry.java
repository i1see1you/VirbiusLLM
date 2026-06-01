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
        String enforceMode,
        int canaryPercent,
        String body) {}

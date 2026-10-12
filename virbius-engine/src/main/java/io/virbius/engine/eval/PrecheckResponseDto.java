package io.virbius.engine.eval;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PrecheckResponseDto(
        boolean hit,
        String category,
        @JsonProperty("triggered_rule_id") String triggeredRuleId,
        String reason,
        @JsonProperty("llm_response") String llmResponse,
        String error,
        @JsonProperty("rule_count") int ruleCount) {}

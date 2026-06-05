package io.virbius.engine.eval;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PromptSimulateResponseDto(
        boolean hit,
        @JsonProperty("llm_hit_rule") boolean llmHitRule,
        @JsonProperty("triggered_id") String triggeredId,
        String reason,
        String error) {}

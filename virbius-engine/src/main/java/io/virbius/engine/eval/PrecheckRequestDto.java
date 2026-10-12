package io.virbius.engine.eval;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PrecheckRequestDto(
        @JsonProperty("tenant_id") String tenantId,
        String content) {}

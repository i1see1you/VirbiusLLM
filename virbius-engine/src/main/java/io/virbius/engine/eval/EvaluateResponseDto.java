package io.virbius.engine.eval;

import java.util.List;

public record EvaluateResponseDto(
        List<SignalDto> signals,
        EngineDecisionDto decision,
        String ruleId,
        int ruleRevision,
        String reasonCode,
        String traceId,
        boolean degraded) {}
